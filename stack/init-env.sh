#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
env_file="$here/.env"
[ -f "$env_file" ] && { echo "stack/.env exists; leaving it"; exit 0; }

lan_ip=$(hostname -I | awk '{print $1}')
[ -n "$lan_ip" ] || { echo "stack/init-env.sh: no LAN address found" >&2; exit 1; }

while IFS= read -r line; do
  case "$line" in
    STACK_HOST=) echo "STACK_HOST=${lan_ip//./-}.sslip.io" ;;
    *_PASSWORD= | *_SECRET=) echo "${line}$(openssl rand -hex 24)" ;;
    *) echo "$line" ;;
  esac
done <"$here/.env.example" >"$env_file"
chmod 600 "$env_file"
echo "wrote stack/.env for ${lan_ip//./-}.sslip.io"
