import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APPS = {"onesa": ROOT / "apps/onesa", "taifafeki-client": ROOT / "apps/taifafeki-client"}
SOURCES = (".kt", ".ts", ".tsx", ".mjs", "strings.xml")
SKIP_DIRS = {"node_modules", "build", "dist", ".output", ".tanstack", ".vinxi"}
# Tests, test fixtures and the generated route tree are not the apps' screens; the CLI is the demo control and lives in tools/.
SKIP_PARTS = ("/jvmTest/", "/commonTest/", "/androidTest/", "/src/testing/")
SKIP_NAMES = (".test.ts", ".test.tsx", "routeTree.gen.ts")

PATTERNS = {
    "journey navigation": re.compile(r"\bJ[1-4](?:\.[0-9]|[ab])?\b|Proceed to|\bJourney\b"),
    "date override": re.compile(
        r"setSystemTime|StoryClock|StoryDates?\b|story[ -]?date|today[ -]?picker|todayPicker|dateOverride|overrideDate|overrideToday|setToday|fakeNow|MockClock|FixedClock|useFakeTimers"
    ),
    "inspector": re.compile(r"[Ii]nspect|DebugPanel|debugPanel|[Dd]ebug (?:panel|screen|menu)|[Dd]evtools|DevTools"),
}

# (file suffix, rule) -> why the match is not what the rule forbids.
EXCLUDED = {
    ("shared/src/commonMain/kotlin/dev/ohs/trustdemo/onesa/feature/wallet/ShareScreen.kt", "date override"): (
        "its DatePicker picks the share's end date between the real today and the last allowed day; it moves no clock"
    ),
}


def files():
    for app in APPS.values():
        for path in sorted(app.rglob("*")):
            relative = path.relative_to(app).parts
            if not path.is_file() or SKIP_DIRS & set(relative):
                continue
            name = path.as_posix()
            if not name.endswith(SOURCES) or name.endswith(SKIP_NAMES) or any(part in name for part in SKIP_PARTS):
                continue
            yield path


def is_comment(line: str) -> bool:
    stripped = line.strip()
    return stripped.startswith(("//", "/*", "*", "{/*", "<!--"))


def main():
    found = []
    scanned = 0
    for path in files():
        scanned += 1
        for number, line in enumerate(path.read_text().splitlines(), 1):
            if is_comment(line):
                continue
            for rule, pattern in PATTERNS.items():
                if not pattern.search(line):
                    continue
                relative = path.relative_to(ROOT).as_posix()
                if any(relative.endswith(suffix) and rule == excluded for (suffix, excluded) in EXCLUDED):
                    continue
                found.append(f"{rule}: {relative}:{number}: {line.strip()[:90]}")
    for app in APPS.values():
        manifest = app / "package.json"
        if manifest.exists() and re.search(r"devtools", manifest.read_text(), re.I):
            found.append(f"inspector: {manifest.relative_to(ROOT)} depends on a devtools package")
    if scanned < 100:
        print(f"FAIL  12 no journey, date override or inspector: scanned only {scanned} files, expected 100 or more")
        return 1
    if found:
        print(f"FAIL  12 no journey, date override or inspector: {len(found)} match(es) in {scanned} files")
        for item in found:
            print(f"      {item}")
        return 1
    print(f"OK    12 no journey, date override or inspector: {scanned} files, {len(EXCLUDED)} exclusion(s) (ShareScreen's expiry DatePicker)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
