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
    ssh) gcloud compute ssh "$name" --project "$project" --zone "$zone" --tunnel-through-iap ;;
    *)
        usage
        exit 1
        ;;
esac
