#!/usr/bin/env bash
set -uo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(cd "$here/../.." && pwd)
export PYTHONDONTWRITEBYTECODE=1
failures=0

run() {
  if ! "$@"; then failures=$((failures + 1)); fi
}

run python3 "$here/data_scan.py"
run python3 "$here/repo_scan.py"
run python3 "$here/static_check.py"

log=$(mktemp)
if (cd "$root" && ./gradlew -q :apps:onesa:shared:jvmTest --rerun --tests 'dev.ohs.trustdemo.onesa.feature.ScreensAgreementTest') >"$log" 2>&1; then
  echo "OK    13 every registered renderer has its ViewDefinition, ViewJoinMap and ViewConfig (ScreensAgreementTest)"
else
  echo "FAIL  13 screens agreement (ScreensAgreementTest)"
  grep -E "AssertionError|screens without|^\s+(\[|[A-Za-z]+.*(is missing|declares|no renderer|has no ViewDefinition))|What went wrong|error:|e: " "$log" | head -30 | sed 's/^/      /'
  failures=$((failures + 1))
fi
rm -f "$log"

[ "$failures" -eq 0 ]
