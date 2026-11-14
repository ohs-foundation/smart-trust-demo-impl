#!/usr/bin/env bash
set -euo pipefail

# smart-trust-tools commit that the JVM and JS builds of hcert-core are pinned to.
commit=d94e21d23d798ab309d57d7c4bb2ee570a80e8fc
src="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/build/smart-trust-tools"

if [ "$(git -C "$src" rev-parse HEAD 2>/dev/null)" != "$commit" ]; then
  rm -rf "$src"
  git init --quiet "$src"
  git -C "$src" fetch --quiet --depth 1 https://github.com/litlfred/smart-trust-tools.git "$commit"
  git -C "$src" checkout --quiet FETCH_HEAD
fi
echo "$src"
