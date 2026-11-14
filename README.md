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

The gateway builds the sibling fork `ohs-player-reference-backend-fork` (branch `trust-demo-household`), so clone it next to this repository.
Set `PLUGIN_SRC` in `stack/.env` to the upstream git URL to build upstream instead.

Medplum's super-admin settings in `stack/.env` apply only when its database is first created, so a stack created before them needs `make stack-reset` once.

| Service | Address |
| --- | --- |
| Onesa Keycloak | `keycloak.onesa.<lan-ip>.sslip.io:8480` |
| Onesa FHIR gateway | `gateway.onesa.<lan-ip>.sslip.io:8480` |
| Taifafeki Medplum | `medplum.kesaro.<lan-ip>.sslip.io:8480` |
| Trust-list host | `trust.gdhcn.<lan-ip>.sslip.io:8480` |

Logins `vilay`, `chanthason`, `bounmy` and `trustdemo-admin` share the password in
`stack/.env` (`TRUSTDEMO_USER_PASSWORD`).

Licensed under Apache-2.0.
