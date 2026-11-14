#!/usr/bin/env bash
set -euo pipefail

# TEST ONLY: a per-machine demo CA and the https endpoints' certificates: VHL links must be https,
# and browsers give the facility client its camera and Web Crypto only over https.
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
tls="$here/tls"
stack_host=$(grep '^STACK_HOST=' "$here/.env" | cut -d= -f2)
mkdir -p "$tls"

if [ ! -f "$tls/ca.crt" ] || ! openssl x509 -in "$tls/ca.crt" -noout -checkend 34560000 >/dev/null; then
  openssl ecparam -name prime256v1 -genkey -noout -out "$tls/ca.key"
  openssl req -x509 -new -key "$tls/ca.key" -sha256 -days 730 -out "$tls/ca.crt" \
    -subj "/CN=Trust demo local CA (TEST ONLY)" \
    -addext "basicConstraints=critical,CA:true" -addext "keyUsage=critical,keyCertSign"
  rm -f "$tls/vhl.crt" "$tls/kesaro.crt"
fi

issue() {
  local name=$1 sans
  shift
  sans=$(printf 'DNS:%s,' "$@")
  sans=${sans%,}
  if [ ! -f "$tls/$name.crt" ] || ! openssl x509 -in "$tls/$name.crt" -noout -checkend 2592000 >/dev/null ||
    [ "$(openssl x509 -in "$tls/$name.crt" -noout -ext subjectAltName | tail -n +2 | tr -d ' ')" != "$sans" ]; then
    openssl ecparam -name prime256v1 -genkey -noout -out "$tls/$name.key"
    openssl req -new -key "$tls/$name.key" -subj "/CN=$1" -out "$tls/$name.csr"
    openssl x509 -req -in "$tls/$name.csr" -CA "$tls/ca.crt" -CAkey "$tls/ca.key" -CAcreateserial \
      -days 365 -sha256 -out "$tls/$name.crt" \
      -extfile <(printf 'subjectAltName=%s\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:false\n' "$sans")
    rm -f "$tls/$name.csr" "$tls/ca.srl"
  fi
}

issue vhl "vhl.onesa.$stack_host"
issue kesaro "client.kesaro.$stack_host" "medplum.kesaro.$stack_host"

chmod 600 "$tls"/*.key
chmod 644 "$tls"/*.crt
echo "stack/tls ready for $stack_host"
