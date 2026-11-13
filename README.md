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

Licensed under Apache-2.0.
