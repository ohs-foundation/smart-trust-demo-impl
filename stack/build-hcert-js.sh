#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
commit=$(grep -m1 '^commit=' "$root/stack/smart-trust-tools.sh" | cut -d= -f2)
out="$root/build/hcert-js"

if [ "$(cat "$out/.commit" 2>/dev/null)" = "$commit" ]; then
  echo "build/hcert-js is already built at ${commit:0:7}"
  exit 0
fi

src=$("$root/stack/smart-trust-tools.sh")

gradle -p "$src/tools/hcert-kotlin" :hcert-core:compileProductionLibraryKotlinJs --stacktrace

rm -rf "$out"
mkdir -p "$out"
cp -R "$src/tools/hcert-kotlin/build/js/packages/smart-trust-kotlin-hcert-core/." "$out/"
rm -rf "$out/node_modules"
echo "$commit" >"$out/.commit"
