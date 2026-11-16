#!/usr/bin/env bash
set -uo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
set -a
# shellcheck source=/dev/null
. "${1:-$here/.env}"
set +a

if [ -n "${STACK_HOST:-}" ]; then
  live=0
  base="${STACK_HOST}:${PROXY_PORT}"
  tls="${STACK_HOST}:${VHL_TLS_PORT}"
  keycloak="http://keycloak.onesa.${base}"
  gateway="http://gateway.onesa.${base}"
  medplum="http://medplum.kesaro.${base}"
  trust="http://trust.gdhcn.${base}"
  vhl="https://vhl.onesa.${tls}"
  client="https://client.kesaro.${tls}"
  medplum_tls="https://medplum.kesaro.${tls}"
  hapi_port=$HAPI_HOST_PORT
  ca=(--cacert "$here/tls/ca.crt")
else
  live=1
  keycloak="https://$KEYCLOAK_HOST"
  gateway="https://$GATEWAY_HOST"
  medplum="https://$MEDPLUM_HOST"
  trust="https://$TRUST_HOST"
  vhl="https://$VHL_HOST"
  client="https://$CLIENT_HOST"
  medplum_tls=$medplum
  hapi_port=$HAPI_FHIR_PORT
  ca=(--proto =https)
fi
echo "expects a stack seeded by 'trust-demo reset'"
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

check "HAPI metadata on loopback" 200 "$(status "http://127.0.0.1:${hapi_port}/fhir/metadata")"
check "Keycloak realm discovery" 200 \
  "$(status "$keycloak/realms/onesa/.well-known/openid-configuration")"
check "gateway refuses a request without a token" 401 "$(status "$gateway/fhir/Patient")"

token=$(curl -s --max-time 10 \
  -d grant_type=password -d client_id=trust-demo-cli -d "client_secret=${TRUSTDEMO_CLI_CLIENT_SECRET}" \
  -d username=vilay -d "password=${TRUSTDEMO_USER_PASSWORD}" \
  "$keycloak/realms/onesa/protocol/openid-connect/token" | jq -r '.access_token // empty')
check "Keycloak issues vilay a token" yes "$([ -n "$token" ] && echo yes || echo no)"
since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
rid=check-$(date +%s)
patients=$(status -H "Authorization: Bearer $token" -H "X-Request-ID: $rid" "$gateway/fhir/Patient")
check "gateway serves vilay's Patient search" 200 "$patients"
[ "$patients" = 403 ] && echo "      vilay has no Practitioner yet: run 'trust-demo reset' before this check"
check "gateway serves vilay a Patient read" 200 \
  "$(status -H "Authorization: Bearer $token" "$gateway/fhir/Patient/TrustDemoBounmyPatient")"
check "gateway audits that read" yes \
  "$(curl -s --max-time 10 --get --data-urlencode "entity=Patient/TrustDemoBounmyPatient" -d action=R \
    --data-urlencode "date=ge$since" "http://127.0.0.1:${hapi_port}/fhir/AuditEvent" |
    jq -e '.entry | length > 0' >/dev/null && echo yes || echo no)"

check "the gateway writes one audit per search" 1 \
  "$(curl -s --max-time 10 --get -d action=E -d _count=100 \
    --data-urlencode "subtype=http://hl7.org/fhir/restful-interaction|search-type" \
    --data-urlencode "date=ge$since" "http://127.0.0.1:${hapi_port}/fhir/AuditEvent" |
    jq --arg rid "$rid" '[.entry[]?.resource|select(any(.entity[]?; .what.identifier.value==$rid))]|length')"

check "VHL service health over TLS" 200 "$(status "${ca[@]}" "$vhl/vhl/health")"
[ "$live" -eq 1 ] || check "VHL has no plain-HTTP route" 404 "$(status "http://vhl.onesa.${base}/vhl/health")"

check "Medplum health" 200 "$(status "$medplum/healthcheck")"
check "Medplum issues the super-admin client a token" yes \
  "$(curl -s --max-time 10 -d grant_type=client_credentials -d "client_id=${MEDPLUM_SUPER_ADMIN_CLIENT_ID}" \
    -d "client_secret=${MEDPLUM_SUPER_ADMIN_CLIENT_SECRET}" "$medplum/oauth2/token" |
    jq -e '.access_token' >/dev/null && echo yes || echo no)"

mp_token=$(curl -s --max-time 10 -d grant_type=client_credentials -d "client_id=${MEDPLUM_SUPER_ADMIN_CLIENT_ID}" \
  -d "client_secret=${MEDPLUM_SUPER_ADMIN_CLIENT_SECRET}" "$medplum/oauth2/token" | jq -r '.access_token // empty')
mp_project=$(curl -s --max-time 10 -H "Authorization: Bearer $mp_token" --get \
  --data-urlencode "name=Taifafeki Ministry of Public Health" "$medplum/fhir/R4/Project" | jq -r '.entry[0].resource.id // empty')
hn_profile=http://smart.who.int/trust-demo/StructureDefinition/TrustDemoTaifafekiPatient
mp_patient() {
  jq -n --arg project "$mp_project" --arg profile "$hn_profile" "{resourceType: \"Patient\",
    meta: {project: \$project, profile: [\$profile]}} + $1" |
    curl -s --max-time 10 -w '\n%{http_code}' -H "Authorization: Bearer $mp_token" \
      -H 'Content-Type: application/fhir+json' --data-binary @- "$medplum/fhir/R4/Patient"
}
broken=$(mp_patient '{gender: "male"}')
check "Medplum refuses a Taifafeki Patient missing the profile's elements" 400 "${broken##*$'\n'}"
conforming=$(mp_patient '{identifier: [{system: "http://smart.who.int/trust-demo/sid/xxr-kesaro-hn", value: "CHECK-HN-0"}],
  name: [{family: "Check", given: ["Profile"]}], birthDate: "2025-10-28"}')
check "Medplum accepts a conforming Taifafeki Patient" 201 "${conforming##*$'\n'}"
created=$(printf '%s' "${conforming%$'\n'*}" | jq -r '.id // empty')
[ -z "$created" ] || curl -s -o /dev/null --max-time 10 -X POST -H "Authorization: Bearer $mp_token" \
  -H 'Content-Type: application/fhir+json' -d '{"resourceType":"Parameters"}' "$medplum/fhir/R4/Patient/$created/\$expunge"

check "Facility client health over TLS" 200 "$(status "${ca[@]}" "$client/")"
[ "$live" -eq 1 ] || check "Facility client's plain-HTTP address redirects to TLS" 301 "$(status "http://client.kesaro.${base}/")"
check "Facility client sends sign-in to Medplum with a PKCE challenge" yes \
  "$(curl -s -o /dev/null -w '%{redirect_url}' --max-time 10 "${ca[@]}" "$client/signin" |
    grep -q "^$medplum_tls/oauth2/authorize?.*code_challenge_method=S256" && echo yes || echo no)"
[ "$live" -eq 1 ] || check "Medplum health over TLS" 200 "$(status --cacert "$here/tls/ca.crt" "https://medplum.kesaro.${tls}/healthcheck")"

served=$(curl -s --max-time 10 "$trust/local-test-list.json")
check "trust host serves the local test list" same \
  "$([ "$served" = "$(cat "$here/../testkit/local-test-list.json")" ] && echo same || echo different)"
check "trust host serves the DEV mirror" yes \
  "$(curl -s --max-time 10 "$trust/dev/v2/trustlist/did.json" | jq -e '.verificationMethod | length > 0' >/dev/null && echo yes || echo no)"

[ "$failures" -eq 0 ]
