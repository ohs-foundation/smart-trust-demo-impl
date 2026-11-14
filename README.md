# smart-trust-demo-impl

An implementation of the [WHO SMART Trust Demonstrator IG](https://github.com/litlfred/smart-trust-demo)
across two countries: Taifafeki, served by Medplum, and Onesa, served by the
[OHS](https://github.com/ohs-foundation) stack. All countries and people are fictional test data.

```
apps/onesa/        Onesa player app (Kotlin Multiplatform)
  shared/            common code; Android and JVM targets
  android/           Android application
  desktop/           desktop application
libs/trustkit/     test keys and trust lists (JVM)
tools/control/     control CLI (JVM)
stack/             local stack for both countries (Docker Compose)
ig/                the IG's FHIR resources, generated at a pinned commit
testkit/           TEST ONLY keys, certificates and trust lists
build-logic/       Spotless conventions
ig-codegen/        Gradle plugin that generates player code from the IG
```

## Build and test

Requires JDK 21 and an Android SDK (`sdk.dir` in `local.properties`; see `local.properties.sample`).
Publish `hcert-core` to the local Maven repository first, from
[smart-trust-tools](https://github.com/litlfred/smart-trust-tools) at `d94e21d`:

```
gradle -p <smart-trust-tools>/tools/hcert-kotlin publishToMavenLocal   # Gradle 8.x
make build
make test
```

## Local stack

Onesa (Keycloak, HAPI FHIR, the OHS FHIR gateway), Taifafeki (Medplum) and a stand-in GDHCN
trust-list host, behind one proxy. Phones and emulators on the same network reach it at
`<service>.<country>.<lan-ip>.sslip.io:8480`; HAPI listens on `127.0.0.1:18082` only.

```
make stack-up      # writes stack/.env on first run, then builds and starts everything
make stack-check   # every service answers, trust lists served
make stack-down
make stack-reset   # removes volumes and stack/.env; needed after a realm change
```

The gateway builds the sibling clone `ohs-player-reference-backend-fork` on branch `trust-demo-household`, so clone it next to this repository. The branch is not published yet; the owner decides where it lives.
An existing `stack/.env` from before needs `PLUGIN_SRC` updated by hand, or run `make stack-reset`.
Upstream lacks the household checker and the Subscription policy: family users are denied everything and Subscriptions are unrestricted for the admin. Point `PLUGIN_SRC` at upstream only to compare behaviour.

The VHL endpoint is HTTPS on 8443 (`vhl.onesa.<lan-ip>.sslip.io`) with a local TEST ONLY CA in `stack/tls/`, created by `make stack-up`.
Clients that fetch manifests (the facility client's server, Phase 6) trust `stack/tls/ca.crt`.
The `vhl` image copies `services/vhl/build/install`, so start the stack with `make stack-up`; a bare `docker compose up --build` needs `./gradlew :services:vhl:installDist` first.

Medplum's super-admin settings in `stack/.env` apply only when its database is first created, so a stack created before them needs `make stack-reset` once.

| Service | Address |
| --- | --- |
| Onesa Keycloak | `keycloak.onesa.<lan-ip>.sslip.io:8480` |
| Onesa FHIR gateway | `gateway.onesa.<lan-ip>.sslip.io:8480` |
| Taifafeki Medplum | `medplum.kesaro.<lan-ip>.sslip.io:8480` |
| Trust-list host | `trust.gdhcn.<lan-ip>.sslip.io:8480` |

Logins `vilay`, `chanthason`, `bounmy` and `trustdemo-admin` share the password in
`stack/.env` (`TRUSTDEMO_USER_PASSWORD`).

## Control CLI

`trust-demo` drives the story from outside the apps (`./gradlew :tools:control:installDist`).

```
trust-demo journeys   # each app's box with its journeys, live from the records; also heads status
trust-demo            # in a terminal: arrow-key menu (map, current step, QR, inspect, watch, move time, reset)
```

Piped, or without colour or Unicode, the map falls back to ASCII; `trust-demo` alone prints help when not interactive.

Licensed under Apache-2.0.
