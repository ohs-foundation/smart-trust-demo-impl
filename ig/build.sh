#!/usr/bin/env bash
set -euo pipefail

IG_REPO=https://github.com/litlfred/smart-trust-demo.git
IG_COMMIT=6512c3b17a510b6e7c8c0fd33fd58a6822930c39

for tool in git node npm python3; do
  command -v "$tool" >/dev/null || { echo "ig/build.sh: $tool is required" >&2; exit 1; }
done

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
trap 'exit 1' INT TERM

git init --quiet "$work/ig"
git -C "$work/ig" fetch --quiet --depth 1 "$IG_REPO" "$IG_COMMIT"
git -C "$work/ig" checkout --quiet FETCH_HEAD

# fhir_cache.py and SUSHI both use ~/.fhir; keep it away from the real one.
export HOME="$work/home"
mkdir -p "$HOME"
cd "$work/ig"
npm ci --ignore-scripts --no-audit --no-fund --prefix tools/ig-gen
python3 tools/ig-gen/fhir_cache.py
tools/ig-gen/node_modules/.bin/sushi build .

generated="$work/ig/fsh-generated/resources"
if [ "${1:-}" = "--check" ]; then
  diff -r "$generated" "$here/resources" && echo "ig/resources matches $IG_COMMIT"
else
  rm -rf "$here/resources"
  mkdir -p "$here/resources"
  cp "$generated"/*.json "$here/resources/"
fi
