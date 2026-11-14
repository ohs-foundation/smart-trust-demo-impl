# make qa

Spec checks 6, 10, 12 and 13, each printing one `OK` or `FAIL` line with a reason and never a secret value. `make qa` exits non-zero on any failure and needs the stack up (`make stack-up`, seeded by `trust-demo reset`).

| Check | Script | Reads |
| --- | --- | --- |
| 6 server | `data_scan.py` | every HAPI resource (loopback) and every Medplum project's resources (super-admin client from `stack/.env`) for the Argon2 hash, passcode fields and IPS content; the subject's name in the receipt Provenance is not IPS content |
| 10 | `repo_scan.py`, allow-list in `hosts.py` | tracked and unignored files for hostnames and PEM private keys |
| 12 | `static_check.py` | both apps' sources for journey labels, date overrides and inspectors |
| 13 | `ScreensAgreementTest` in `:apps:onesa:shared:jvmTest` | `AppViewRegistry.kt` and its registrations against `composeResources/files/{states,configs}` |

Check 6 in the browser is not part of `make qa`. At the end of the live walkthrough, with the facility client's browser exposing a DevTools endpoint, run `node tools/qa/browser_scan.mjs http://127.0.0.1:9222 https://client.kesaro.<STACK_HOST>:<VHL_TLS_PORT>` (needs `playwright-core` resolvable). From a Playwright script, `import { scanBrowserStorage } from "./tools/qa/browser_scan.mjs"` and call `await scanBrowserStorage(context, origin)`, which returns `{ ok, problems, counts }`.
