#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
env_file="$here/.env"
touch "$env_file"
chmod 600 "$env_file"

if [ -z "${STACK_HOST:-}" ]; then
  lan_ip=$(ip route get 1.1.1.1 2>/dev/null | awk '{for (i = 1; i < NF; i++) if ($i == "src") print $(i + 1)}')
  [ -n "$lan_ip" ] || { echo "stack/init-env.sh: no LAN address found; set STACK_HOST" >&2; exit 1; }
  STACK_HOST="${lan_ip//./-}.sslip.io"
fi

# Keys already in .env are kept: Postgres keeps the passwords it was first started with.
while IFS= read -r line; do
  key=${line%%=*}
  case "$line" in '' | \#*) continue ;; esac
  grep -q "^${key}=" "$env_file" && continue
  case "$line" in
    STACK_HOST=) echo "STACK_HOST=$STACK_HOST" ;;
    *_PASSWORD= | *_SECRET=) echo "${line}$(openssl rand -hex 24)" ;;
    *_CLIENT_ID=) echo "${line}$(python3 -c 'import uuid; print(uuid.uuid4())')" ;;
    *) echo "$line" ;;
  esac >>"$env_file"
done <"$here/.env.example"
echo "stack/.env ready for $(grep '^STACK_HOST=' "$env_file" | cut -d= -f2)"
