# smart-trust-demo-impl

The [WHO SMART Trust Demonstrator IG](https://github.com/litlfred/smart-trust-demo) made executable
across two fictional countries, with a stand-in GDHCN trust-list host between them. All countries and
people are fictional test data.

| | Country | Server | Apps |
| --- | --- | --- | --- |
| Taifafeki | Medplum | Medplum | Taifafeki Facility Client (web): hospital EMR and clinic viewer |
| Onesa | HAPI behind the OHS gateway, Keycloak, the VHL service | HAPI | Onesa player app (Android): CHW, clinician and family roles |

```
Taifafeki · facility client                Onesa · Onesa app
┌─────────────────────────┐
│ J1  Dr Anan, hospital   │  newborn entry, print cards
│     EMR                 │
└───────────┬─────────────┘
            │ paper cards (DVC QR)
            ▼
            ┌─────────────────────────┐
            │ J2  Vilay, CHW phone    │  scan card, register household
            │     (offline)           │  (outbox uploads on signal)
            └───────────┬─────────────┘
                        │ sync + referral
                        ▼
            ┌─────────────────────────┐
            │ J3a Nurse Chanthason,   │  verify the cards
            │ J3b clinic tablet       │  match doses, schedule next
            │ Year                    │  a year of Onesan doses
            └─────────────────────────┘
            ┌─────────────────────────┐
            │ J4.0 Bounmy, family     │  share records, consent
            │      wallet             │
            └───────────┬─────────────┘
                        │ VHL QR + passcode
┌───────────────────────▼─┐
│ J4.1 Dr Anan, clinic    │  scan link, passcode, view summary
│      viewer             │
└─────────────────────────┘
```

| Step | Who | App | Screens |
| --- | --- | --- | --- |
| J1 Discharge and cards | Dr Anan | Facility Client, hospital EMR | newborn entry, S1, print cards |
| J2 Household registration | Vilay (CHW) | Onesa app, phone, offline | scan card, S2, registration form, S3 |
| J3a Verify the cards | Nurse Chanthason | Onesa app, tablet | referral inbox, S4 |
| J3b Map and schedule | Nurse Chanthason | Onesa app, tablet | dose match picker, S5 |
| Year | Nurse Chanthason | Onesa app, tablet | record a dose |
| J4.0 Share health records | Bounmy | Onesa app, family phone | S6, share and consent, S7 |
| J4.1 View the summary | Dr Anan | Facility Client, clinic viewer | scan link and passcode, S8 |

## Repo layout

| Path | What |
| --- | --- |
| `apps/onesa/` | Onesa player app (Kotlin Multiplatform): `shared/`, `android/`, `desktop/` |
| `apps/taifafeki-client/` | Taifafeki Facility Client (React, with a small Node server) |
| `services/vhl/` | VHL service: share, passcode, withdraw, ITI-YY5 and ITI-68 |
| `libs/hcert/`, `libs/trustkit/` | health-certificate verification; test keys, trust lists and card signer |
| `tools/control/` | `trust-demo`, the control CLI |
| `tools/qa/` | `make qa` checks |
| `stack/` | local stack for both countries (Docker Compose) |
| `ig/` | the IG's FHIR resources, generated at a pinned commit |
| `testkit/` | TEST ONLY keys, certificates and trust lists |
| `build-logic/`, `ig-codegen/` | Spotless conventions; Gradle plugin that generates player code from the IG |

## Run it locally

Requires JDK 21 (JDK 25 fails the Gradle build), Docker, Node with pnpm, and an Android SDK for the app.
`hcert-core` 0.1.0 comes from [smart-trust-tools](https://github.com/litlfred/smart-trust-tools) at `d94e21d`:

```
gradle -p <smart-trust-tools>/tools/hcert-kotlin publishToMavenLocal   # Gradle 8.x
```

The gateway builds the sibling clone `ohs-player-reference-backend-fork` on branch `trust-demo-household`,
so clone it next to this repository. Upstream lacks the household checker and the Subscription policy;
point `PLUGIN_SRC` at upstream only to compare behaviour.

```
make stack-up      # writes stack/.env and the TEST ONLY CA on first run, then builds and starts everything
trust-demo reset   # both countries to the story's starting state (below)
make stack-check   # every service answers, trust lists served
make stack-down
make stack-reset   # removes volumes and stack/.env; needed after a realm change
```

Hostnames are `<service>.<country>.<lan-ip>.sslip.io`, so phones on the same network reach the stack
without DNS setup. If the machine's LAN IP changes, rerun `make stack-up`.

| Service | Address |
| --- | --- |
| Onesa Keycloak | `http://keycloak.onesa.<lan-ip>.sslip.io:8480` |
| Onesa FHIR gateway | `http://gateway.onesa.<lan-ip>.sslip.io:8480` |
| Onesa VHL service | `https://vhl.onesa.<lan-ip>.sslip.io:8443` |
| Taifafeki Medplum | `http://medplum.kesaro.<lan-ip>.sslip.io:8480` (also on `https`, 8443) |
| Taifafeki Facility Client | `https://client.kesaro.<lan-ip>.sslip.io:8443` |
| Trust-list host | `http://trust.gdhcn.<lan-ip>.sslip.io:8480` |

HAPI listens on `127.0.0.1:18082` only. `make stack-up` creates a per-machine demo CA in `stack/tls/`
(TEST ONLY); trust `stack/tls/ca.crt` once in the demo browser or tablet, since camera scanning needs
a secure context. Logins `vilay`, `chanthason`, `bounmy` and `trustdemo-admin` share the password in
`stack/.env` (`TRUSTDEMO_USER_PASSWORD`). Medplum's super-admin settings apply only when its database
is first created, so a stack made before them needs `make stack-reset` once.

### Build the app

Settings come from environment variables, which win over `local.properties`; see
`local.properties.sample` for the keys. Point them at your stack, then build:

```
export OAUTH_ISSUER=http://keycloak.onesa.<lan-ip>.sslip.io:8480/realms/ohs-player
export FHIR_BASE_URL=http://gateway.onesa.<lan-ip>.sslip.io:8480/fhir/
export VHL_BASE_URL=https://vhl.onesa.<lan-ip>.sslip.io:8443
export TRUST_LIST_URL=http://trust.gdhcn.<lan-ip>.sslip.io:8480/local-test-list.json
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :apps:onesa:android:assembleDebug
```

The debug build trusts `stack/tls/ca.crt`, so `make stack-up` comes first. Sign in with the story's users.

### Test

| Command | Covers |
| --- | --- |
| `./gradlew check` (or `make test`) | Kotlin unit and integration tests, Spotless |
| `pnpm test` in `apps/taifafeki-client/` | the facility client (`pnpm typecheck` too) |
| `make stack-check` | the running stack, after `trust-demo reset` |
| `make qa` | repo scans, static checks, screens agreement |
| `make ig-check` | `ig/resources/` matches the pinned IG |

## Control CLI

`trust-demo` drives the story from outside the apps (`./gradlew :tools:control:installDist`, then
`tools/control/build/install/control/bin/trust-demo`).

| Command | Does |
| --- | --- |
| `reset` | both countries to the starting state; uploads Somsak's cards, registers the webhook |
| `status` | the current step, worked out from the records (`--current` prints only it) |
| `journeys` | each app's box with its journeys, live from the records; also heads `status` |
| `inspect [J1\|J2\|J3a\|J3b\|Year\|J4.0\|J4.1]` | the records behind the current or named step (`--json` in full) |
| `watch` | follows the story live from HAPI's webhook and a poll |
| `qr <card>` | prints a test QR (`--text` for pasting) |
| `time move J4.0` | shifts every story date so J4.0 falls today (`--dry-run`, `--with-year-doses`) |
| `time expire-consents` | ends the current consents a minute ago |

Run on its own in a terminal, `trust-demo` opens an arrow-key menu (map, current step, QR, inspect,
watch, move time, reset). Piped, or without colour or Unicode, the map falls back to ASCII.

## Walk the story

Start with `make stack-up && trust-demo reset`, and keep `trust-demo watch` open in a second terminal.

| Step | Do | Test QR | CLI shows |
| --- | --- | --- | --- |
| J1 | Dr Anan, Facility Client: enter Somsak's discharge, print his two cards | | `status` moves to J2 once the cards exist |
| J2 | Vilay, Onesa app, phone offline: scan a card, register the household, refer Somsak | `trust-demo qr somsak-hepb`, `somsak-bcg` | `inspect J2`: household, Somsak, referral |
| J3a | Chanthason, tablet: open the referral, scan the cards, verify | the same two cards | `inspect J3a`: verification Provenances |
| J3b | Chanthason: match doses to Onesan products, schedule the next | | `inspect J3b`: ConceptMap entry, schedule |
| Year | Chanthason: record doses, or `trust-demo time move J4.0 --with-year-doses` | | `inspect Year` |
| J4.0 | Bounmy, family wallet: share Somsak's records, set a passcode, consent | | `inspect J4.0`: shares, links |
| J4.1 | Dr Anan, Facility Client viewer: paste the link, enter the passcode, view the summary | the link from the wallet | `inspect J4.1`: access AuditEvents |

`trust-demo time move J4.0` goes between J3 and J4.0 (it refuses while shares exist); try it with `--dry-run` first.

J2 works offline: registrations wait in an outbox on the phone and upload when signal returns.
`trust-demo qr who-hepb` and `who-bcg` print WHO's published XXR example cards.

Refusal paths, all at J4.1:

| Case | How |
| --- | --- |
| Wrong passcode, attempts left | enter a wrong passcode; the viewer shows the attempts remaining |
| Locked | three wrong passcodes lock the share for good; Bounmy re-shares to recover |
| Withdrawn | Bounmy withdraws the link in the wallet, then Dr Anan opens it |
| Expired | `trust-demo time expire-consents`, then open the link |
| Unknown key | paste the output of `trust-demo qr unknown-key-link --text` |

## Departures from the IG

- The VHL service is HTTPS only (hcert-core and the IHE VHL rule require it).
- Verification and adjudication Provenances also target the Patient, so staff apps can write and sync them through the gateway.
- Missed doses are caught up at the next visit: every uncounted dose due on or before it is due then.
- The receipt Provenance names the holder ("VHL for <name>"), as the IG's `TrustDemoJ41Received` example does.
- The app is named "Taifafeki Facility Client" where the IG's device frame says "OHS Facility Client 2.4.1".
- SDC's form layout is kept as it is.
- A verified dose's CVX comes from a product table, since the IG has no rule.
- Hep B's `vaccineCode.text` is the schedule display.
- Registration works offline through an outbox; a registration the gateway refuses keeps its local records, marked refused with the reason on the Sync tab.
- Fractional seconds are trimmed from downloaded records, because fhir-engine `2.0.0-alpha04` cannot index them.

## Security boundaries

- Everything in `testkit/` is TEST ONLY and public on purpose.
- The demo CA in `stack/tls/` is per machine and local only; never trust it elsewhere.
- Never commit `stack/.env`, `stack/tls/` or `stack/taifafeki/secrets/`.
- HAPI has no authentication and stays internal: loopback only, reached through the gateway.

## Deploying

Deployment goes through a fork of `ohs-foundation/ohs-player-reference-infrastructure`, documented there.

Licensed under Apache-2.0.
