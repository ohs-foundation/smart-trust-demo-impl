#!/usr/bin/env bash
set -euo pipefail

# smart-trust-tools commit that the JVM build of hcert-core is pinned to.
commit=d94e21d23d798ab309d57d7c4bb2ee570a80e8fc
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
src="$root/build/smart-trust-tools"
out="$root/build/hcert-js"

if [ "$(cat "$out/.commit" 2>/dev/null)" = "$commit" ]; then
  echo "build/hcert-js is already built at ${commit:0:7}"
  exit 0
fi

if [ "$(git -C "$src" rev-parse HEAD 2>/dev/null)" != "$commit" ]; then
  rm -rf "$src"
  git init --quiet "$src"
  git -C "$src" fetch --quiet --depth 1 https://github.com/litlfred/smart-trust-tools.git "$commit"
  git -C "$src" checkout --quiet FETCH_HEAD
fi

gradle -p "$src/tools/hcert-kotlin" :hcert-core:compileProductionLibraryKotlinJs --stacktrace

rm -rf "$out"
mkdir -p "$out"
cp -R "$src/tools/hcert-kotlin/build/js/packages/smart-trust-kotlin-hcert-core/." "$out/"
rm -rf "$out/node_modules"
echo "$commit" >"$out/.commit"
