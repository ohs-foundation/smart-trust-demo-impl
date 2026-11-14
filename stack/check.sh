#!/usr/bin/env bash
set -uo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
set -a
# shellcheck source=/dev/null
. "$here/.env"
set +a

base="${STACK_HOST}:${PROXY_PORT}"
failures=0

check() {
  local name=$1 expected=$2 actual=$3
  if [ "$actual" = "$expected" ]; then
    echo "OK    $name"
  else
    echo "FAIL  $name (expected $expected, got $actual)"
    failures=$((failures + 1))
  fi
}

status() { curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$@"; }

check "HAPI metadata on loopback" 200 "$(status "http://127.0.0.1:${HAPI_HOST_PORT}/fhir/metadata")"
check "Keycloak realm discovery" 200 \
  "$(status "http://keycloak.onesa.${base}/realms/ohs-player/.well-known/openid-configuration")"
check "gateway refuses a request without a token" 401 "$(status "http://gateway.onesa.${base}/fhir/Patient")"

token=$(curl -s --max-time 10 \
  -d grant_type=password -d client_id=trust-demo-cli -d "client_secret=${TRUSTDEMO_CLI_CLIENT_SECRET}" \
  -d username=vilay -d "password=${TRUSTDEMO_USER_PASSWORD}" \
  "http://keycloak.onesa.${base}/realms/ohs-player/protocol/openid-connect/token" | jq -r '.access_token // empty')
check "Keycloak issues vilay a token" yes "$([ -n "$token" ] && echo yes || echo no)"
check "gateway serves vilay's Patient search" 200 \
  "$(status -H "Authorization: Bearer $token" "http://gateway.onesa.${base}/fhir/Patient")"

check "Medplum health" 200 "$(status "http://medplum.kesaro.${base}/healthcheck")"
check "Medplum issues the super-admin client a token" yes \
  "$(curl -s --max-time 10 -d grant_type=client_credentials -d "client_id=${MEDPLUM_SUPER_ADMIN_CLIENT_ID}" \
    -d "client_secret=${MEDPLUM_SUPER_ADMIN_CLIENT_SECRET}" "http://medplum.kesaro.${base}/oauth2/token" |
    jq -e '.access_token' >/dev/null && echo yes || echo no)"

served=$(curl -s --max-time 10 "http://trust.gdhcn.${base}/local-test-list.json")
check "trust host serves the local test list" same \
  "$([ "$served" = "$(cat "$here/../testkit/local-test-list.json")" ] && echo same || echo different)"
check "trust host serves the DEV mirror" yes \
  "$(curl -s --max-time 10 "http://trust.gdhcn.${base}/dev/v2/trustlist/did.json" | jq -e '.verificationMethod | length > 0' >/dev/null && echo yes || echo no)"

[ "$failures" -eq 0 ]
