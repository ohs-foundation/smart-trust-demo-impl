#!/usr/bin/env bash
# Runs the server side of docs/deploy.md from the laptop, over IAP, using config.yaml and ../.env.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
checkout=smart-trust-demo-impl

setting() { sed -n "s/^$1: *//p" "$here/config.yaml" | tr -d '"' | head -1; }

project="$(setting project_id)"
zone="$(setting zone)"
name="$(setting server_name)"
name="${name:-trust-demo}"
github="$(setting github_repository)"
branch="${BRANCH:-$(git -C "$repo" branch --show-current)}"
registry_host="$(sed -n 's/^REGISTRY=//p' "$repo/infra/.env" | cut -d/ -f1)"

env_value() { sed -n "s/^$1=//p" "$repo/infra/.env" | head -1; }
host_of() {
    local value
    value="$(env_value "$1")"
    echo "${value//\$\{BASE_DOMAIN\}/$(env_value BASE_DOMAIN)}"
}

remote() { gcloud compute ssh "$name" --project "$project" --zone "$zone" --tunnel-through-iap -- "$@"; }
copy() { gcloud compute scp --project "$project" --zone "$zone" --tunnel-through-iap "$@"; }

usage() {
    cat <<EOF
Usage: $0 <command>
  setup                clone or update $checkout at branch '$branch', give your user Docker and
                       registry access, and copy infra/.env up
  nginx                ./dev.sh nginx: host vhosts and one certificate per name
  up [flags]           ./dev.sh up --pull [flags], e.g. --taifafeki --pipes
  cli                  build trust-demo here and ship it to the server
  trust-demo <args>    run the CLI on the server against infra/.env
  check                stack/check.sh infra/.env on the server
  status               container health on the server, then every public endpoint and certificate
  ssh                  a shell on the server
EOF
}

case "${1:-}" in
    setup)
        [[ -n "$github" ]] || { echo "Set github_repository in config.yaml" >&2; exit 1; }
        remote "set -e
            if [ -d $checkout/.git ]; then
                git -C $checkout fetch -q origin $branch
                git -C $checkout checkout -q $branch
                git -C $checkout pull -q --ff-only origin $branch
            else
                git clone -q -b $branch https://github.com/$github $checkout
            fi
            sudo usermod -aG docker \"\$USER\"
            gcloud auth configure-docker $registry_host --quiet"
        copy "$repo/infra/.env" "$name:$checkout/infra/.env"
        remote "cd $checkout/infra && chmod 600 .env && sed -i 's|^GATEWAY_PLUGIN_DIR=.*|GATEWAY_PLUGIN_DIR=/tmp|' .env"
        ;;
    nginx) remote "cd $checkout/infra && ./dev.sh nginx" ;;
    up)
        shift
        remote "cd $checkout/infra && ./dev.sh up --pull $*"
        ;;
    cli)
        (cd "$repo" && ./gradlew -q :tools:control:installDist)
        remote "mkdir -p $checkout/tools/control/build/install"
        copy --recurse "$repo/tools/control/build/install/control" "$name:$checkout/tools/control/build/install/"
        ;;
    trust-demo)
        shift
        remote "cd $checkout && tools/control/build/install/control/bin/trust-demo $* --stack-env infra/.env"
        ;;
    check) remote "cd $checkout && stack/check.sh infra/.env" ;;
    status)
        remote 'docker ps -a --format "{{.Names}}\t{{.Status}}" | sort; free -h | sed -n 1,2p'
        failed=0
        probe() {
            local code
            code="$(curl -s -o /dev/null -m 20 -w '%{http_code}' "$2")"
            if [[ "$code" == "$3" ]]; then echo "OK    $1"; else echo "FAIL  $1: expected $3, got $code ($2)"; failed=1; fi
        }
        probe "Keycloak realm" "https://$(host_of KEYCLOAK_HOST)/realms/$(env_value KEYCLOAK_REALM)/.well-known/openid-configuration" 200
        probe "gateway metadata" "https://$(host_of GATEWAY_HOST)/fhir/metadata" 200
        probe "gateway refuses no token" "https://$(host_of GATEWAY_HOST)/fhir/Patient" 401
        probe "VHL service" "https://$(host_of VHL_HOST)/vhl/health" 200
        probe "trust list" "https://$(host_of TRUST_HOST)/local-test-list.json" 200
        probe "Medplum" "https://$(host_of MEDPLUM_HOST)/healthcheck" 200
        probe "Taifafeki client" "https://$(host_of CLIENT_HOST)/" 200
        probe "web portal" "https://$(host_of WEB_HOST)/" 200
        for name in KEYCLOAK_HOST GATEWAY_HOST WEB_HOST VHL_HOST TRUST_HOST MEDPLUM_HOST CLIENT_HOST; do
            h="$(host_of "$name")"
            until="$(echo | openssl s_client -connect "$h:443" -servername "$h" 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)"
            echo "cert  $h: ${until:-none}"
        done
        exit "$failed"
        ;;
    ssh) gcloud compute ssh "$name" --project "$project" --zone "$zone" --tunnel-through-iap ;;
    *)
        usage
        exit 1
        ;;
esac
