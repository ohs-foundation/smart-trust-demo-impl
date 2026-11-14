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

Derived from `ohs-foundation/ohs-player-reference-infrastructure@5c87af6`.
