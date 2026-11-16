#!/bin/bash
set -euo pipefail

# =============================================================================
# dev.sh — server lifecycle
#
# Subcommands:
#   init      Create .env from .env.example with a random secret for every [generated]
#   render    Render the host nginx vhosts from .env
#   nginx     Install host vhosts and take TLS certificates
#   up        Render configs and start services (--pipes adds the analytics pipeline and Superset,
#             --taifafeki adds Medplum and the facility client; --pull fetches the images from REGISTRY
#             instead of building, as does a checkout without sources)
#   push      Build the demo's images and push them to REGISTRY (laptop or CI)
#   down      Stop all running services
#   reset     Stop services and wipe named volumes
#   logs      Tail logs (all services or one)
#   status    Show service state
#   help      Show this help
# =============================================================================

# --- Colours -----------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*" >&2; exit 1; }

# --- Paths -------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"

# --- Host nginx --------------------------------------------------------------
# Overridable for distributions that lay nginx out differently.
NGINX_SITES_AVAILABLE="${NGINX_SITES_AVAILABLE:-/etc/nginx/sites-available}"
NGINX_SITES_ENABLED="${NGINX_SITES_ENABLED:-/etc/nginx/sites-enabled}"
CERTBOT_WEBROOT="${CERTBOT_WEBROOT:-/var/www/certbot}"
LETSENCRYPT_LIVE="${LETSENCRYPT_LIVE:-/etc/letsencrypt/live}"

# --- Prerequisites -----------------------------------------------------------
# The package name differs on every platform, so "install gettext-base" is
# useless advice elsewhere. Name the package for each instead.
install_hint() {
    case "$1" in
        envsubst)
            printf '%s\n' \
                "  Debian / Ubuntu : sudo apt install gettext-base" \
                "  Fedora / RHEL   : sudo dnf install gettext" \
                "  Alpine          : apk add gettext"
            ;;
        buildx)
            printf '%s\n' \
                "  Debian / Ubuntu : sudo apt install docker-buildx-plugin" \
                "  Fedora / RHEL   : sudo dnf install docker-buildx-plugin" \
                "  Arch            : sudo pacman -S docker-buildx"
            ;;
        secret)
            printf '%s\n' \
                "  Secrets come from /dev/urandom via od, which is present on virtually" \
                "  every system. If you are seeing this, /dev/urandom is unreadable and" \
                "  neither openssl nor python3 is installed. Install openssl or python3."
            ;;
    esac
}

require_tool() {
    command -v "$1" >/dev/null 2>&1 && return 0
    error "$(printf '%s is required but was not found on your PATH.\n\n%s' "$1" "$(install_hint "$1")")"
}

check_prerequisites() {
    command -v docker >/dev/null 2>&1 || error "Docker is not installed."
    docker compose version >/dev/null 2>&1 \
        || error "The Docker Compose v2 plugin is not installed (v2.29 or newer is required)."
    # The gateway image uses `RUN --mount=type=cache` and named build contexts, which only
    # BuildKit understands. Fail here rather than minutes into `up`, after the pulls.
    docker buildx version >/dev/null 2>&1 \
        || error "$(printf 'The Docker Buildx plugin is not installed, and the images need it to build.\n\n%s' "$(install_hint buildx)")"
    require_tool envsubst
    generate_secret >/dev/null 2>&1 \
        || error "$(printf 'Cannot generate secrets on this machine.\n\n%s' "$(install_hint secret)")"
}

# --- Env file ----------------------------------------------------------------
# If .env does not exist, copy .env.example to create it, then replace every value set to the
# literal marker [generated] with a unique random secret. Values already set are never changed.
# /dev/urandom read through od is the widest-available source: od is POSIX and ships in GNU
# coreutils, BSD userland and busybox alike. Returns non-zero if no method works.
generate_secret() {
    if [[ -r /dev/urandom ]] && command -v od >/dev/null 2>&1; then
        od -An -N24 -tx1 /dev/urandom | tr -d ' \n'
    elif command -v openssl >/dev/null 2>&1; then
        openssl rand -hex 24
    elif command -v python3 >/dev/null 2>&1; then
        python3 -c 'import secrets; print(secrets.token_hex(24))'
    else
        return 1
    fi
}

generate_uuid() {
    cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen 2>/dev/null \
        || python3 -c 'import uuid; print(uuid.uuid4())'
}

require_env_file() {
    if [[ ! -f "$ENV_FILE" ]]; then
        local example="$SCRIPT_DIR/.env.example"
        [[ -f "$example" ]] || error ".env.example not found at $example"
        info "No .env found — creating from .env.example..."
        cp "$example" "$ENV_FILE"
        chmod 600 "$ENV_FILE"
    fi
    if grep -q '=\[\(generated\|uuid\)\]' "$ENV_FILE"; then
        info "Replacing [generated] markers in .env with random secrets..."
        # Bash substitution, not `sed -i`, which differs between GNU and BSD. Assign first, then
        # substitute: a failing generate_secret inside the expansion would not trip `set -e`.
        local tmp line secret id
        tmp="$(mktemp "${TMPDIR:-/tmp}/ohs-env.XXXXXX")" \
            || error "Cannot create a temporary file to rewrite .env."
        while IFS= read -r line || [[ -n "$line" ]]; do
            while [[ "$line" == *"=[generated]"* ]]; do
                secret="$(generate_secret)"
                # Hex, so an unquoted `&` in the replacement can never corrupt the line.
                [[ -n "$secret" && "$secret" != *[!0-9a-f]* ]] \
                    || { rm -f "$tmp"; error "generate_secret produced an empty or non-hexadecimal value; refusing to write unusable credentials to .env."; }
                line="${line/"=[generated]"/=$secret}"
            done
            if [[ "$line" == *"=[uuid]"* ]]; then
                id="$(generate_uuid)"
                line="${line/"=[uuid]"/=$id}"
            fi
            printf '%s\n' "$line"
        done < "$ENV_FILE" > "$tmp"
        cat "$tmp" > "$ENV_FILE"
        rm -f "$tmp"
        info "Secrets generated. Review at: $ENV_FILE"
    fi
}

load_env() {
    require_env_file
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
}

# --- Template rendering ------------------------------------------------------
# envsubst with an explicit variable list keeps nginx's own $host and $scheme intact. Renders to a
# temp file first and replaces the target only when the bytes differ.
RENDER_CHANGED=0
render() {
    local src="$SCRIPT_DIR/$1"
    local dst="$SCRIPT_DIR/$2"
    local vars="$3"
    local tmp
    [[ -f "$src" ]] || error "Template missing: $1"
    tmp="$(mktemp "${TMPDIR:-/tmp}/ohs-render.XXXXXX")" \
        || error "Cannot create a temporary file to render $2."
    envsubst "$vars" < "$src" > "$tmp"
    if [[ -f "$dst" ]] && cmp -s "$tmp" "$dst"; then
        rm -f "$tmp"
        return 0
    fi
    cat "$tmp" > "$dst"
    rm -f "$tmp"
    chmod 644 "$dst"
    RENDER_CHANGED=$((RENDER_CHANGED + 1))
    info "  rendered $2"
}

render_templates() {
    load_env
    RENDER_CHANGED=0

    render data-pipes/config/postgres-analytics.json.example \
           data-pipes/config/postgres-analytics.json \
           '${POSTGRES_ADMIN_PASSWORD}'

    render nginx/host/keycloak.conf.example \
           nginx/host/keycloak.conf \
           '${KEYCLOAK_HOST} ${KEYCLOAK_PORT}'

    render nginx/host/gateway.conf.example \
           nginx/host/gateway.conf \
           '${GATEWAY_HOST} ${FHIR_GATEWAY_PORT}'

    export APP_LINK_DIR="${APP_LINK_DIR:-/var/lib/trust-demo/app-links}"
    render nginx/host/web.conf.example \
           nginx/host/web.conf \
           '${WEB_HOST} ${OHS_PLAYER_WEB_PORT} ${APP_LINK_DIR}'

    render nginx/host/vhl.conf.example \
           nginx/host/vhl.conf \
           '${VHL_HOST} ${VHL_PORT}'

    render nginx/host/trust.conf.example \
           nginx/host/trust.conf \
           '${TRUST_HOST} ${TRUST_PORT}'

    render nginx/host/medplum.conf.example \
           nginx/host/medplum.conf \
           '${MEDPLUM_HOST} ${MEDPLUM_PORT}'

    render nginx/host/client.conf.example \
           nginx/host/client.conf \
           '${CLIENT_HOST} ${CLIENT_PORT} ${MEDPLUM_HOST}'

    # Rendering the analytics vhosts with an unset hostname would write `server_name ;`, which
    # nginx refuses to load, so they are written only when named.
    if [[ -n "${PIPELINE_HOST:-}" ]]; then
        render nginx/host/pipeline.conf.example \
               nginx/host/pipeline.conf \
               '${PIPELINE_HOST} ${PIPELINE_PORT}'
    fi

    if [[ -n "${SUPERSET_HOST:-}" ]]; then
        render nginx/host/superset.conf.example \
               nginx/host/superset.conf \
               '${SUPERSET_HOST} ${SUPERSET_PORT}'
    fi

    if (( RENDER_CHANGED == 0 )); then
        info "Configuration is up to date."
    fi
}

# --- Compose helpers ---------------------------------------------------------
# The Taifafeki add-on loads only with `up --taifafeki`; every other command sees both files so that
# down, reset, logs and status reach whatever is running.
COMPOSE_FILES=(-f docker-compose.yaml -f docker-compose.taifafeki.yaml)

compose() {
    (cd "$SCRIPT_DIR" && docker compose "${COMPOSE_FILES[@]}" "$@")
}

# compose, prefixed with the --profile flags parse_profiles selected. The `+` expansion keeps an
# empty array from counting as unbound under `set -u` on bash 3.2.
compose_profiles() {
    compose ${PROFILE_ARGS[@]+"${PROFILE_ARGS[@]}"} "$@"
}

parse_profiles() {
    PROFILE_ARGS=()
    PULL_IMAGES=0
    COMPOSE_FILES=(-f docker-compose.yaml)
    for arg in "$@"; do
        case "$arg" in
            --pipes)     PROFILE_ARGS+=(--profile pipes) ;;
            --taifafeki) COMPOSE_FILES=(-f docker-compose.yaml -f docker-compose.taifafeki.yaml) ;;
            --pull)      PULL_IMAGES=1 ;;
            *)           error "Unknown flag: $arg (expected --pipes, --taifafeki or --pull)" ;;
        esac
    done
}

# --- Subcommands -------------------------------------------------------------
cmd_init() {
    check_prerequisites
    load_env
    info "Ready. Set the hostnames and GATEWAY_PLUGIN_DIR in $ENV_FILE, then run './dev.sh nginx' and './dev.sh up'."
}

cmd_up() {
    parse_profiles "$@"
    check_prerequisites
    render_templates
    # Bind-mounting a missing file would create a directory; `trust-demo reset` fills these in.
    local secrets="$SCRIPT_DIR/../stack/taifafeki/secrets"
    mkdir -p "$secrets"
    touch "$secrets/medplum-client-id" "$secrets/clinic-viewer.key" "$secrets/clinic-viewer.crt"
    chmod 600 "$secrets/clinic-viewer.key"
    if [[ -n "${REGISTRY:-}" && ( "$PULL_IMAGES" == 1 || ! -f "$SCRIPT_DIR/../gradlew" ) ]]; then
        info "Pulling images from $REGISTRY..."
        compose_profiles pull
        compose_profiles up -d --no-build
        return
    fi
    # --ignore-buildable: the gateway is built from source and exists in no registry, so a plain
    # `pull` fails and set -e would abort before anything starts.
    info "Checking for image updates..."
    compose_profiles pull --ignore-buildable
    info "Building images and starting services. The first build takes several minutes..."
    compose_profiles up -d --build
}

# Builds the demo's own images on this machine and pushes them under the git SHA (or the given tag)
# and latest. Log in first: gcloud auth configure-docker <region>-docker.pkg.dev.
cmd_push() {
    check_prerequisites
    load_env
    [[ -n "${REGISTRY:-}" ]] || error "Set REGISTRY in .env to the registry output of infra/gcp."
    local tag="${1:-$(git -C "$SCRIPT_DIR" rev-parse --short HEAD)}" name
    shift || true
    local -a all=(fhir-gateway vhl issuer taifafeki-client web-portal)
    local -a names=("$@")
    (( ${#names[@]} )) || names=("${all[@]}")
    for name in "${names[@]}"; do
        [[ " ${all[*]} " == *" $name "* ]] || error "Unknown image: $name. Choose from ${all[*]}."
    done
    if [[ " ${names[*]} " == *" vhl "* ]]; then (cd "$SCRIPT_DIR/.." && ./gradlew :services:vhl:installDist); fi
    if [[ " ${names[*]} " == *" issuer "* ]]; then (cd "$SCRIPT_DIR/.." && ./gradlew :services:issuer:installDist); fi
    if [[ " ${names[*]} " == *" taifafeki-client "* ]]; then make -C "$SCRIPT_DIR/.." hcert-js; fi
    COMPOSE_FILES=(-f docker-compose.yaml -f docker-compose.taifafeki.yaml)
    IMAGE_TAG="$tag" compose build "${names[@]}"
    for name in "${names[@]}"; do
        docker tag "$REGISTRY/$name:$tag" "$REGISTRY/$name:latest"
        docker push "$REGISTRY/$name:$tag"
        docker push "$REGISTRY/$name:latest"
    done
    info "Pushed ${names[*]} as $tag and latest."
}

cmd_down() {
    check_prerequisites
    info "Stopping stack..."
    compose --profile pipes down
}

cmd_reset() {
    check_prerequisites
    warn "This will stop all services and delete named volumes (database data will be lost)."
    compose --profile pipes down --volumes
    info "Reset complete. Run './dev.sh up' to start fresh."
}

cmd_logs() {
    check_prerequisites
    if [[ $# -eq 0 ]]; then
        compose logs -f
    else
        compose logs -f "$1"
    fi
}

cmd_status() {
    check_prerequisites
    compose ps
}

cmd_render() {
    check_prerequisites
    render_templates
}

# --- Host nginx --------------------------------------------------------------
# Root already has the privileges, so calling sudo would only fail where it is not installed.
as_root() {
    if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then "$@"; else sudo "$@"; fi
}

# Let's Encrypt keeps live/ readable by root only.
has_certificate() {
    as_root test -f "$LETSENCRYPT_LIVE/$1/fullchain.pem"
}

# A vhost serving nothing but the ACME challenge. The rendered vhosts reference certificates that
# do not exist yet, so nginx would refuse to load them and certbot could never obtain the very
# certificates they need. This breaks that cycle for the port 80 phase.
bootstrap_vhost() {
    cat <<EOF
# Temporary, written by './dev.sh nginx' so certbot can answer the HTTP-01 challenge for $1.
# Replaced by the rendered vhost once the certificate exists.
server {
    listen 80;
    listen [::]:80;
    server_name $1;

    location /.well-known/acme-challenge/ {
        root $CERTBOT_WEBROOT;
    }

    location / {
        return 404;
    }
}
EOF
}

nginx_reload() {
    as_root nginx -t || error "nginx rejected the configuration. Nothing was reloaded."
    if command -v systemctl >/dev/null 2>&1; then
        as_root systemctl reload nginx || warn "Could not reload nginx via systemctl."
    else
        as_root nginx -s reload || warn "Could not signal nginx to reload."
    fi
}

enable_site() {
    local file="$1" base
    base="$(basename "$file")"
    as_root cp "$file" "$NGINX_SITES_AVAILABLE/$base"
    as_root ln -sfn "$NGINX_SITES_AVAILABLE/$base" "$NGINX_SITES_ENABLED/$base"
}

# Installs the rendered vhosts and obtains a certificate per subdomain, one certbot run each so a
# single failing name does not cost the others their certificate.
cmd_nginx() {
    command -v nginx >/dev/null 2>&1 || error "nginx is not installed on this host."
    require_env_file
    load_env
    render_templates

    local -a hosts=("${KEYCLOAK_HOST:-}" "${GATEWAY_HOST:-}" "${WEB_HOST:-}" \
                    "${VHL_HOST:-}" "${TRUST_HOST:-}" "${MEDPLUM_HOST:-}" "${CLIENT_HOST:-}" \
                    "${PIPELINE_HOST:-}" "${SUPERSET_HOST:-}")
    local -a files=("keycloak.conf" "gateway.conf" "web.conf" \
                    "vhl.conf" "trust.conf" "medplum.conf" "client.conf" \
                    "pipeline.conf" "superset.conf")
    local -a pending=()
    local i host file

    as_root mkdir -p "$CERTBOT_WEBROOT" "$NGINX_SITES_AVAILABLE" "$NGINX_SITES_ENABLED"

    for i in "${!hosts[@]}"; do
        host="${hosts[$i]}"
        [[ -n "$host" ]] || { warn "Skipping ${files[$i]}, its hostname is unset in .env."; continue; }
        if has_certificate "$host"; then
            info "Certificate already present for $host."
        else
            pending+=("$i")
        fi
    done

    # Phase one. Only the hosts still needing a certificate get a bootstrap vhost, so hosts that
    # already have one keep serving from their real config throughout.
    if (( ${#pending[@]} )); then
        info "Installing temporary port 80 vhosts for the ACME challenge..."
        for i in "${pending[@]}"; do
            host="${hosts[$i]}"
            bootstrap_vhost "$host" | as_root tee "$NGINX_SITES_AVAILABLE/${files[$i]}" >/dev/null
            as_root ln -sfn "$NGINX_SITES_AVAILABLE/${files[$i]}" "$NGINX_SITES_ENABLED/${files[$i]}"
            echo "    $host -> ${files[$i]}"
        done
        nginx_reload

        command -v certbot >/dev/null 2>&1 || error "certbot is not installed on this host."
        local -a certbot_args=(certonly --webroot -w "$CERTBOT_WEBROOT")
        if [[ -n "${CERTBOT_EMAIL:-}" ]]; then
            certbot_args+=(--non-interactive --agree-tos -m "$CERTBOT_EMAIL")
        fi

        for i in "${pending[@]}"; do
            host="${hosts[$i]}"
            info "Requesting a certificate for $host..."
            as_root certbot "${certbot_args[@]}" -d "$host" \
                || warn "certbot failed for $host. Its vhost stays on port 80 only."
        done
    fi

    # Phase two. Only install a rendered vhost where the certificate it references now exists,
    # otherwise nginx would refuse to load and take the working sites down with it.
    info "Installing the rendered vhosts..."
    for i in "${!hosts[@]}"; do
        host="${hosts[$i]}"; file="$SCRIPT_DIR/nginx/host/${files[$i]}"
        [[ -n "$host" && -f "$file" ]] || continue
        if has_certificate "$host"; then
            enable_site "$file"
            echo "    $host -> ${files[$i]}"
        else
            warn "No certificate for $host, leaving its temporary port 80 vhost in place."
        fi
    done
    nginx_reload
    info "Host nginx updated."
}

# --- Usage -------------------------------------------------------------------
usage() {
    cat <<EOF
Usage: $0 <command> [options]

Commands:
  init                        Create .env from .env.example with generated secrets
  render                      Render the host nginx vhosts from .env
  nginx                       Install the host nginx vhosts and take a TLS
                              certificate per subdomain. Needs sudo, nginx
                              and certbot.
  up [--pipes] [--taifafeki] [--pull]  Render configs and start services
                              --pipes      adds the analytics pipeline + Superset
                              --taifafeki  adds Medplum and the facility client
                              --pull       pulls from REGISTRY instead of building
  push [tag] [image...]       Build the demo's images (all four, or those named) and push
                              them to REGISTRY as the git SHA (or tag) and latest
  down                        Stop all running services
  reset                       Stop services and wipe named volumes
  logs [service]              Tail logs (all services or one)
  status                      Show service state
  help                        Show this help

First-run setup:
  ./dev.sh init
  \${EDITOR:-vi} .env
  ./dev.sh nginx
  ./dev.sh up
EOF
}

# --- Main --------------------------------------------------------------------
main() {
    local command="${1:-help}"
    shift || true
    case "$command" in
        init)     cmd_init ;;
        render)   cmd_render ;;
        nginx)    cmd_nginx ;;
        up)       cmd_up "$@" ;;
        push)     cmd_push "$@" ;;
        down)     cmd_down ;;
        reset)    cmd_reset ;;
        logs)     cmd_logs "$@" ;;
        status)   cmd_status ;;
        help|--help|-h) usage ;;
        *)        error "Unknown command: $command. Run '$0 help' for usage." ;;
    esac
}

main "$@"
