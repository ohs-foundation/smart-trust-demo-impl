# Deploy

One Google Cloud VM runs both countries behind host nginx with Let's Encrypt certificates. Images are built on a laptop and pulled by the server.

```
laptop                                        Google Cloud
┌──────────────────────┐   push images   ┌─ Artifact Registry
│ infra/gcp  tofu apply├────────────────▶│
│ infra/.env, dev.sh   │                 └────────────┬ pull
│ trust-demo (build)   │   scp over IAP  ┌────────────▼────────────────┐
│                      ├────────────────▶│ VM: repo checkout, infra/.env│
└──────────────────────┘                 │ dev.sh up, trust-demo, nginx │
                                         └────────────▲────────────────┘
 phones (APK) ─── https://<svc>.<country>.<base domain> :443
```

Placeholders below: `<region>` is the registry's region, `my-trust-demo` the project, `demo.example.org` the base domain, `trust-demo` the VM and `europe-west3` / `europe-west3-a` the region and zone. Use your own.

## Prerequisites

| Where | Needs |
| --- | --- |
| Laptop | JDK 21, Docker with Buildx, Node with pnpm, `make`, `gcloud`, [OpenTofu](https://opentofu.org/docs/), a clone of the backend fork next to this repo (see the README) |
| Google Cloud | a billing account, and an organisation or folder if you use one |
| DNS | control of `demo.example.org` |

## Steps

| # | Where | Do |
| --- | --- | --- |
| 1 | Laptop | Provision |
| 2 | DNS | Three wildcard records |
| 3 | Laptop | Fill `infra/.env`, push images |
| 4 | Server | Checkout, `.env`, nginx, start |
| 5 | Server | CLI, seed, check |
| 6 | Laptop | Build the APK |
| 7 | Phones | Install, walk the story |

### 1. Provision (laptop)

```
cd infra/gcp
cp config.example.yaml config.yaml    # git-ignored; set project_id, org_id or folder_id, billing_account, base_domain
gcloud auth application-default login
tofu init && tofu plan && tofu apply
tofu output
```

| Output | Use |
| --- | --- |
| `server_ip` | the DNS target |
| `registry` | `REGISTRY` in `infra/.env` |
| `github_workload_identity_provider` | `GCP_WORKLOAD_IDENTITY_PROVIDER` repository variable (CI, below) |
| `github_ci_service_account` | `GCP_CI_SERVICE_ACCOUNT` repository variable (CI, below) |
| `ssh` | a ready `gcloud compute ssh ... --tunnel-through-iap` command |
| `dns_records` | the three records of step 2 |

`machine_type` defaults to `e2-highmem-4` (4 vCPU, 32 GB): the stack needs memory more than CPU, and `e2-standard-4` (16 GB) fits only without the data pipes.

`startup.sh` installs Docker, nginx, certbot, a Java runtime and `jq` on first boot, which takes a few minutes. SSH is open only through IAP; ports 80 and 443 are public. Check the server is ready with the `ssh` output:

```
<ssh output> -- 'docker --version && java -version && jq --version'
```

Set `github_repository: owner/repo` in `config.yaml` to let that repository's CI push images (no keys; Workload Identity Federation limited to it).

### 2. DNS

Point the `dns_records` output at `server_ip`:

```
*.onesa.demo.example.org    A  <server_ip>
*.kesaro.demo.example.org   A  <server_ip>
*.gdhcn.demo.example.org    A  <server_ip>
```

Check they resolve before step 4's certificates: `dig +short keycloak.onesa.demo.example.org` prints `<server_ip>`.

### 3. Images (laptop)

```
cd infra
./dev.sh init          # writes .env with generated secrets
```

Edit `infra/.env`:

| Variable | Value |
| --- | --- |
| `BASE_DOMAIN` | `demo.example.org`; the `*_HOST` names derive from it |
| `REGISTRY` | the `registry` output |
| `IMAGE_TAG` | `latest`, or the tag you push |
| `CERTBOT_EMAIL` | the address Let's Encrypt registers |
| `GATEWAY_PLUGIN_DIR` | the backend fork checkout on this machine |

`./dev.sh init` leaves an existing `.env` alone: add any key `.env.example` has that it lacks (`REGISTRY` and `IMAGE_TAG` arrived later).

The web portal bakes `WEB_HOST` and `KEYCLOAK_HOST` into its image, so push with the `.env` the server will use. Then:

```
gcloud auth configure-docker <region>-docker.pkg.dev
./dev.sh push          # or ./dev.sh push v1; pushes the tag and latest
```

`push [tag] [image...]` runs `./gradlew :services:vhl:installDist` and `make hcert-js` as the chosen images need, then builds and pushes `fhir-gateway`, `vhl`, `taifafeki-client` and `web-portal`, or only those named.

**CI builds the rest.** `.github/workflows/artifacts.yml` runs on pushes to `seed-from-template` and `main` (and by hand) and pushes `vhl`, `taifafeki-client` and `web-portal` as the short commit SHA and `latest`. Set these repository variables; a job whose variables are missing is skipped with a notice:

| Variable | Value |
|---|---|
| `LIVE_BASE_DOMAIN` | the base domain, e.g. `demo.example.org` |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | `github_workload_identity_provider` output |
| `GCP_CI_SERVICE_ACCOUNT` | `github_ci_service_account` output |
| `REGISTRY` | `registry` output |

```
gh variable set LIVE_BASE_DOMAIN -R owner/repo -b demo.example.org
```

(and the same for the other three). A public repository's Actions logs are public, so the base domain can appear in them.

CI never builds `fhir-gateway`: its plugin comes from a private fork. Push it from the laptop with `GATEWAY_PLUGIN_DIR` set: `./dev.sh push <sha> fhir-gateway`, using the SHA CI tagged so the server pulls one `IMAGE_TAG` for all four.

### 4. Server

The compose files mount `stack/onesa`, `testkit/` and build `services/vhl` by path, so the server needs a checkout.

Once the repo is public, clone it on the server (the `ssh` output opens a shell):

```
git clone <repo url> ~/smart-trust-demo-impl
```

Before then, ship an archive from the laptop at the repo root:

```
git archive --prefix=smart-trust-demo-impl/ -o repo.tar.gz HEAD
gcloud compute scp repo.tar.gz trust-demo:~ --project my-trust-demo --zone europe-west3-a --tunnel-through-iap
```

and on the server `tar xzf repo.tar.gz`.

Copy the laptop's `.env` over. It is generated once, so both sides must hold the same file:

```
gcloud compute scp infra/.env trust-demo:~/smart-trust-demo-impl/infra/.env --project my-trust-demo --zone europe-west3-a --tunnel-through-iap
```

On the server, in `~/smart-trust-demo-impl/infra`:

```
chmod 600 .env
gcloud auth configure-docker <region>-docker.pkg.dev    # once
./dev.sh nginx                      # vhosts and one certificate per name
./dev.sh up --pull --taifafeki      # add --pipes for the analytics pipeline and Superset
```

`--pull` is needed because the checkout has `gradlew`, which otherwise makes `up` build from source. `GATEWAY_PLUGIN_DIR` must be set but can be any path while pulling.

### 5. Seed and check (server)

`trust-demo` needs only a Java 21 runtime, its install directory and the `.env`. The IG resources and the WHO example card are packed into its jar. It writes the facility client's secrets to `stack/taifafeki/secrets/`, resolved from the `.env` path as `../stack/taifafeki/secrets`, so run it inside the checkout. `stack/check.sh` needs `curl` and `jq`.

Install on a VM that predates this change: `sudo apt-get install -y openjdk-21-jre-headless jq`. New VMs get both from `startup.sh`.

Ship the CLI from the laptop:

```
./gradlew :tools:control:installDist
gcloud compute scp --recurse tools/control/build/install/control trust-demo:~/smart-trust-demo-impl/tools/control/build/install/ --project my-trust-demo --zone europe-west3-a --tunnel-through-iap
```

On the server, in `~/smart-trust-demo-impl`:

```
tools/control/build/install/control/bin/trust-demo --stack-env infra/.env reset
./stack/check.sh infra/.env
```

`reset` writes the Taifafeki secrets, so `./dev.sh up --pull --taifafeki` once more afterwards if the facility client started before them. `check.sh` tests every service over HTTPS without the demo CA.

### 6. APK

CI builds a debug APK for test phones when `LIVE_BASE_DOMAIN` is set: open the Actions run, then Artifacts, `onesa-<short-sha>` (kept 14 days). To build a release APK yourself, on the laptop:

Release signing uses `keystore.properties` (`keystore.properties.template`).

```
OAUTH_ISSUER=https://keycloak.onesa.demo.example.org/realms/onesa \
FHIR_BASE_URL=https://gateway.onesa.demo.example.org/fhir/ \
VHL_BASE_URL=https://vhl.onesa.demo.example.org \
TRUST_LIST_URL=https://trust.gdhcn.demo.example.org/local-test-list.json \
./gradlew :apps:onesa:android:assembleRelease
```

Install the APK on the phones. Release trusts the system CAs, so the public certificates need nothing extra.

### 7. Walk the story

Run `trust-demo --stack-env infra/.env watch` on the server and follow [Walk the story](../README.md#walk-the-story). Logins are in `infra/.env` (`TRUSTDEMO_USER_PASSWORD`).

## Update

```
./dev.sh push v2                          # laptop; rebuild and push (CI does this by commit SHA, except the gateway)
# server: set IMAGE_TAG=v2 in infra/.env, then
./dev.sh up --pull --taifafeki
```

With CI images, set `IMAGE_TAG=<sha>` of the pushed commit instead. A changed CLI is shipped again as in step 5. A realm change needs a fresh Keycloak database (`./dev.sh reset` wipes all volumes, then `up` and `trust-demo reset`).

## Tear down

```
cd infra/gcp && tofu destroy
```

This removes the VM, address, registry and network. The project is kept (`deletion_policy = "PREVENT"`); delete it in the console if you want it gone. Remove the DNS records yourself.
