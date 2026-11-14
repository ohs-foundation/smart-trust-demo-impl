#!/usr/bin/env bash
set -euo pipefail

# TEST ONLY: a per-machine demo CA and the VHL endpoint's certificate; the links must be https.
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
tls="$here/tls"
host="vhl.onesa.$(grep '^STACK_HOST=' "$here/.env" | cut -d= -f2)"
mkdir -p "$tls"

if [ ! -f "$tls/ca.crt" ] || ! openssl x509 -in "$tls/ca.crt" -noout -checkend 2592000 >/dev/null; then
  openssl ecparam -name prime256v1 -genkey -noout -out "$tls/ca.key"
  openssl req -x509 -new -key "$tls/ca.key" -sha256 -days 730 -out "$tls/ca.crt" \
    -subj "/CN=Trust demo local CA (TEST ONLY)" \
    -addext "basicConstraints=critical,CA:true" -addext "keyUsage=critical,keyCertSign"
  rm -f "$tls/vhl.crt"
fi

if [ ! -f "$tls/vhl.crt" ] || ! openssl x509 -in "$tls/vhl.crt" -noout -checkend 2592000 >/dev/null ||
  ! openssl x509 -in "$tls/vhl.crt" -noout -ext subjectAltName | grep -q "DNS:$host\$"; then
  openssl ecparam -name prime256v1 -genkey -noout -out "$tls/vhl.key"
  openssl req -new -key "$tls/vhl.key" -subj "/CN=$host" -out "$tls/vhl.csr"
  openssl x509 -req -in "$tls/vhl.csr" -CA "$tls/ca.crt" -CAkey "$tls/ca.key" -CAcreateserial \
    -days 365 -sha256 -out "$tls/vhl.crt" \
    -extfile <(printf 'subjectAltName=DNS:%s\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:false\n' "$host")
  rm -f "$tls/vhl.csr" "$tls/ca.srl"
fi

chmod 600 "$tls"/*.key
chmod 644 "$tls"/*.crt
echo "stack/tls ready for $host"
