# Third-party licenses

This repo is Apache-2.0 (`LICENSE`; no `NOTICE`, because no upstream we copied from ships one). What it uses, shipped or deployed, was checked on 2026-11-14 against POMs, `package.json` and each project's own `LICENSE`.

```
repo (Apache-2.0)
 |- ships:   JVM/KMP libs, npm libs, fonts, icons, IG + WHO test data
 '- deploys: images and OpenTofu, pulled at deploy time, not redistributed
```

## JVM and Kotlin Multiplatform

Runtime classpaths of `apps/onesa` (shared, Android release, desktop), `services/vhl`, `tools/control` and `libs/*`: 624 artifacts, no GPL, AGPL, SSPL, BUSL or non-commercial term.

| License | Artifacts | Notes |
| --- | --- | --- |
| Apache-2.0 | 577 | Ktor, kotlinx, Koin, Room, AndroidX, Compose, Clikt, Mordant, KScan, the OHS `dev.ohs.fhir` libs, `hcert-core` and `hcert-api` |
| MIT | 26 | slf4j, QRose, FileKit, java-jwt, colormath, checker-qual, dbus-java |
| Apache-2.0 and BSD-3-Clause | 3 | `antlr-kotlin-runtime` (2), `camera-core` |
| Bouncy Castle licence (MIT-style) | 3 | `bcprov`, `bcpkix`, `bcutil` 1.86; also the Argon2id passcode hash |
| BSD-3-Clause | 1 | `datastore-preferences-external-protobuf` |
| JNA: LGPL-2.1+ or Apache-2.0 | 3 | used under Apache-2.0 |
| H2: MPL-2.0 or EPL-1.0 | 1 | `services/vhl` embedded database, unmodified; file-level copyleft only |
| Google terms, not open source | 10 | ML Kit and Play services under the ML Kit Terms of Service and Android SDK License; Android only, see below |

| Component | Version | License | Obligations met |
| --- | --- | --- | --- |
| `dev.ohs.fhir` fhir-engine, fhir-data-capture, fhir-workflow | 2.0.0-alpha04, -alpha04, -alpha01 | Apache-2.0 | yes |
| `dev.ohs.fhir` fhir-model (r4, r4b, r5), fhir-path | 1.0.0-rc03, 1.0.0-beta06 | Apache-2.0 | yes |
| `io.github.litlfred:hcert-core`, `hcert-api` | 0.1.0, built from `d94e21d` | Apache-2.0 (POM and the repo's `LICENSE`) | yes |
| Clikt, Mordant | 5.1.0, 3.0.2 | Apache-2.0 | yes |
| Ktor | 3.2.3, 3.5.2 | Apache-2.0 | yes |
| kotlinx (coroutines, serialization, datetime, io) | 1.11.x | Apache-2.0 | yes |
| Room | 2.5.0, 3.0.2 | Apache-2.0 | yes |
| KScan | 0.9.2 | Apache-2.0 | yes; its Android scanner is ML Kit |
| Bouncy Castle | 1.86 | Bouncy Castle licence | yes |
| ML Kit barcode scanning, Play services | 17.3.0, 18.x | Google ML Kit Terms of Service, Android SDK License | accepted for the demo: free, not open source, needs Google Play services. A ZXing-based scanner (Apache-2.0) is the open alternative |
| COSE, CBOR | none | implemented inside `hcert-core` | n/a |

Every library comes from Maven Central, Google Maven or `mavenLocal` (only `io.github.litlfred`, built by `make hcert-js` and `stack/smart-trust-tools.sh`); there is no JitPack dependency.

## npm (`apps/taifafeki-client`)

Production: 228 packages. MIT 194, ISC 14, Apache-2.0 7, BSD-3-Clause 3, MPL-2.0 3, OFL-1.1 2, Python-2.0 1, CC-BY-4.0 1, Unlicense 1, 0BSD 1, no license field 1. With dev tools: 302 packages, adding mostly MIT plus Apache-2.0 5, BSD-2-Clause 2, MIT-0 2, BlueOak-1.0.0 1, CC0-1.0 1.

| Component | Version | License | Obligations met |
| --- | --- | --- | --- |
| Medplum `core`, `fhirtypes`, `react-hooks` | 5.2.13 | Apache-2.0 | yes |
| TanStack Query, Router, Start | 5.105.0, 1.170.46, 1.169.4 | MIT | yes |
| Tailwind CSS | 4.3.3 | MIT | yes |
| `lightningcss` (Tailwind's build engine) | 1.32.0 | MPL-2.0 | yes; unmodified, build tool |
| shadcn/ui components in `src/components/ui/` | copied | MIT, Copyright (c) 2023 shadcn | yes, `LICENSE-shadcn-ui.txt` beside them |
| Radix UI, class-variance-authority, clsx | 1.8.1, 0.7.1, 2.1.1 | MIT, Apache-2.0, MIT | yes |
| jsQR | 1.4.0 | Apache-2.0 | yes |
| `lucide-react` | 1.66.0 | ISC | yes |
| `smart-trust-kotlin-hcert-core` (hcert-js) | 0.1.0, built from `d94e21d` | no field in its `package.json`; source is Apache-2.0 | yes |
| `argparse`, `caniuse-lite` | 2.0.1, 1.0.30001826 | Python-2.0, CC-BY-4.0 | yes; build-time data and tools |

## Vendored assets

| Asset | Where | License | Obligations met |
| --- | --- | --- | --- |
| Plus Jakarta Sans, JetBrains Mono (7 TTFs) | `apps/onesa/shared/.../composeResources/font/` | OFL-1.1, copyright in each file | yes, texts in `files/licenses/` (bundled with the app) |
| the same four fonts | Keycloak theme `resources/fonts/` | OFL-1.1 | yes, `*_ofl.txt` beside them |
| the same fonts, self-hosted by the client | `@fontsource-variable/*` 5.3.0 | OFL-1.1 | yes, each package's `LICENSE` stays in `node_modules`, which the image keeps |
| Theme line icons (info, alert and shield paths) | Keycloak theme `template.ftl` | Lucide ISC, Feather MIT | yes, `LICENSE-lucide.txt` in `resources/img/` |
| Keycloak theme | `stack/onesa/keycloak/themes/onesa/` | the demo's own, on Keycloak's `base` theme (Apache-2.0); `login.ftl` follows Keycloak's structure | yes, Apache-2.0 both ways |
| Brand assets: mark, lockup, app icons, favicons | `docs/brand/` and what its `render.py` writes into `apps/onesa/`, `apps/taifafeki-client/public/` and the Keycloak theme | the project's own, Apache-2.0; the lockup's text is Plus Jakarta Sans (OFL-1.1) outlined | yes |
| `infra/` | derived from `ohs-player-reference-infrastructure@5c87af6` | Apache-2.0, no `NOTICE` upstream, no copyright line in its `LICENSE` | yes, the README names the source and the files are changed in place |

## IG and WHO material

| Material | Source | Stated terms | Redistribution here |
| --- | --- | --- | --- |
| `ig/resources/` (163 FHIR resources) | `litlfred/smart-trust-demo@6512c3b` | `sushi-config.yaml` and the ImplementationGuide resource say CC0-1.0; the source README says Apache-2.0 "to be confirmed"; no `LICENSE` file | permissive either way. CC0-1.0 is declared in `sushi-config.yaml` and the ImplementationGuide resource; the upstream has no `LICENSE` file, and a request to add one is drafted for the owner |
| `testkit/who/root-did.json`, `WHO-signing-did.json`, `libs/trustkit` golden vectors, `libs/hcert` test vectors | `WorldHealthOrganization/smart-trust-tools@d94e21d` | Apache-2.0 (its `LICENSE`) | under Apache-2.0, as that repo ships them |
| `testkit/who/dvc-qr-example-XXR.json` and the two WHO test cards | a conformance vector of `WorldHealthOrganization/smart-trust@af75be2`, carried in smart-trust-tools | smart-trust's `LICENSE`: CC BY-NC-SA 3.0 IGO for the IG text, BSD-3-Clause for software | CC BY-NC-SA 3.0 IGO (non-commercial, share-alike) for the IG text; the repo's Apache-2.0 does not apply to these files, which are used unmodified as test vectors; marked in `testkit/who/README.md` |
| GDHCN DEV trust list and signer in `apps/onesa/.../files/trust/`, `testkit/who/root-did.json` | WHO's DEV trust-list CDN, retrieved 2026-10-23 | no licence stated; public operational data (public keys and DIDs of a DEV network), source URLs in `testkit/who/SOURCE.yaml` and `files/trust/README.md` | kept as published |
| `testkit/` keys and lists | generated here | TEST ONLY, Apache-2.0 | yes |

## Deployed, not shipped

| Component | Version | License | Note |
| --- | --- | --- | --- |
| HAPI FHIR JPA starter | v8.8.0-1 | Apache-2.0 | |
| Keycloak | 26.5.0 | Apache-2.0 | |
| Medplum server | 5.2.13 | Apache-2.0 | |
| PostgreSQL | 16.15, 18, 18.6 | PostgreSQL License | |
| Valkey (Medplum's cache; service `medplum-redis`, Redis protocol) | 8.1.11 | BSD-3-Clause | |
| nginx | 1.27, 1.29.8 | BSD-2-Clause | |
| FHIR gateway (OHS fork) | built from source | Apache-2.0 | |
| FHIR Data Pipes, Apache Superset | `--pipes` profile, not pinned in the compose | Apache-2.0 | |
| Eclipse Temurin, Maven, Node | 21.0.12, 3.9.16, 22 | GPLv2 with Classpath Exception, Apache-2.0, MIT | image bases; the exception allows running our code |
| certbot, Ubuntu 24.04 | apt on the VM | Apache-2.0, mixed (per package) | not redistributed |
| OpenTofu and the Google provider | 1.8+, 7.46.2 | MPL-2.0 | used unmodified |

Medplum's cache runs on Valkey 8 (BSD-3-Clause), not Redis 8, which is RSALv2, SSPLv1 or AGPLv3. Valkey speaks the Redis protocol, so Medplum's `MEDPLUM_REDIS_*` settings and the service name are unchanged.
