# infra

Deploys one country's server: Postgres, Keycloak with the Onesa realm, HAPI, the forked FHIR gateway and the web portal, behind host nginx with a Let's Encrypt certificate per name.

```
internet :443 -> host nginx -> 127.0.0.1: keycloak, gateway, web -> hapi -> postgres
```

```
./dev.sh init     # .env with generated secrets
./dev.sh nginx    # vhosts and certificates
./dev.sh up       # --pipes adds the analytics pipeline and Superset
```

Set `KEYCLOAK_HOST`, `GATEWAY_HOST`, `WEB_HOST` and `GATEWAY_PLUGIN_DIR` (a checkout of the backend fork) in `.env` first. The realm, theme, HAPI config and gateway Dockerfile are the demo's own under `../stack/onesa`.

The VHL service runs from `../services/vhl/Dockerfile`, which packages `services/vhl/build/install/vhl`: run `./gradlew :services:vhl:installDist` in the repo root before `./dev.sh up`. The trust-list host serves the committed TEST ONLY `../testkit` list and DIDs as they are, so nothing needs generating; `./gradlew :libs:trustkit:generateTestKit` re-signs from the same keys and rewrites `testkit/local-test-list.json` and `testkit/gdhcn-signer-did.json`.

Derived from `ohs-foundation/ohs-player-reference-infrastructure@5c87af6`.
