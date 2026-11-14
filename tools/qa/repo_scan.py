import re
import subprocess
import sys
from pathlib import Path

from hosts import hosts_in, host_ok

ROOT = Path(__file__).resolve().parents[2]
SECRET_DIRS = ("stack/.env", "stack/tls", "stack/taifafeki/secrets")
KEY_BLOCK = re.compile(r"-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----")
TOKENS = {
    "AWS access key id": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    "GitHub token": re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,}\b"),
    "Slack token": re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}"),
    "live payment key": re.compile(r"\b[sr]k_live_[A-Za-z0-9]{16,}"),
}
# Pinned archives and lock files carry registry URLs of the package managers, not hosts the demo calls.
# Bare names in code are Java and Kotlin package names; URLs and did:web ids are scanned everywhere.
CODE = (".kt", ".kts", ".java", ".ts", ".tsx", ".mjs")
SKIP_LOCKS = {"apps/taifafeki-client/pnpm-lock.yaml"}


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout


def candidate_files():
    names = git("ls-files", "-z").split("\0") + git("ls-files", "-z", "--others", "--exclude-standard").split("\0")
    return sorted({name for name in names if name})


def main():
    problems = []
    tracked_secrets = [p for p in SECRET_DIRS if git("ls-files", p).strip()]
    problems += [f"{p} is tracked but holds secrets" for p in tracked_secrets]

    readme = (ROOT / "testkit/README.md").read_text()
    test_only = "TEST ONLY" in readme
    hosts_seen = 0
    bad_hosts = {}
    keys = 0
    for name in candidate_files():
        path = ROOT / name
        if not path.is_file() or name in SKIP_LOCKS:
            continue
        try:
            text = path.read_text()
        except (UnicodeDecodeError, OSError):
            continue
        if KEY_BLOCK.search(text):
            keys += 1
            if not name.startswith("testkit/"):
                problems.append(f"{name}: a PEM private key outside testkit/")
            elif not test_only:
                problems.append(f"{name}: testkit/README.md does not say TEST ONLY")
        for label, pattern in TOKENS.items():
            if pattern.search(text):
                problems.append(f"{name}: looks like a {label}")
        bare = not name.endswith(CODE)
        for number, line in enumerate(text.splitlines(), 1):
            for host in hosts_in(line, bare):
                hosts_seen += 1
                if not host_ok(host):
                    bad_hosts.setdefault(host.lower(), []).append(f"{name}:{number}")
    for host, places in sorted(bad_hosts.items()):
        problems.append(f"host {host} is not on the allow-list: {', '.join(places[:3])}{' ...' if len(places) > 3 else ''}")
    if problems:
        print(f"FAIL  10 repo scan: {len(problems)} problem(s)")
        for problem in problems:
            print(f"      {problem}")
        return 1
    print(f"OK    10 repo scan: {hosts_seen} hostnames all on the allow-list, {keys} private keys all under testkit/ (TEST ONLY), no secret files tracked")
    return 0


if __name__ == "__main__":
    sys.exit(main())
