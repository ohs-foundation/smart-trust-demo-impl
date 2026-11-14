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

Taifafeki (Medplum and the facility client) is the add-on `docker-compose.taifafeki.yaml`, started with `./dev.sh up --taifafeki`; it needs `make hcert-js` first and `trust-demo reset` to write its secret files, and it shares nothing with Onesa but public names.

Set `REGISTRY` and `IMAGE_TAG` in `.env` to ship the demo's own images (`fhir-gateway`, `vhl`, `taifafeki-client`, `web-portal`) through a registry (the Portal image bakes in `WEB_HOST` and `KEYCLOAK_HOST`, so push with the server's names); the public images always come from their own.

| Where | Command | Does |
|---|---|---|
| Laptop or CI | `./dev.sh push [tag] [image...]` | runs `installDist` and `hcert-js` as needed, builds the four images (or those named), pushes the git SHA (or tag) and `latest` |
| Server | `./dev.sh up` | pulls instead of building when `REGISTRY` is set and the repo has no `gradlew`; `up --pull` forces it |

Derived from `ohs-foundation/ohs-player-reference-infrastructure@5c87af6`.
