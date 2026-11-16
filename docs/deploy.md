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

Check they resolve before step 4's certificates: `dig +short keycloak.onesa.demo.example.org` prints `<server_ip>`. Behind Cloudflare, set the records to DNS only (grey cloud): its proxy answers with its own addresses and its free certificate covers one level below the domain, not names like `keycloak.onesa.demo.example.org`.

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

`push [tag] [image...]` runs `./gradlew :services:vhl:installDist`, `:services:issuer:installDist` and `make hcert-js` as the chosen images need, then builds and pushes `fhir-gateway`, `vhl`, `issuer`, `taifafeki-client` and `web-portal`, or only those named.

The `issuer` image is Taifafeki's card signer: it needs no hostname, certificate or published port, because only the facility client calls it, over `taifafeki_net`; it mounts the TEST ONLY `testkit/XXR_CARD` key read-only.

**CI builds the rest.** `.github/workflows/artifacts.yml` runs on pushes to `seed-from-template` and `main` (and by hand) and pushes `vhl`, `issuer`, `taifafeki-client` and `web-portal` as the short commit SHA and `latest`. Set these repository variables; a job whose variables are missing is skipped with a notice:

| Variable | Value |
|---|---|
| `LIVE_BASE_DOMAIN` (a **secret**, so the logs mask it) | the base domain, e.g. `demo.example.org` |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | `github_workload_identity_provider` output |
| `GCP_CI_SERVICE_ACCOUNT` | `github_ci_service_account` output |
| `REGISTRY` | `registry` output |

```
gh secret set LIVE_BASE_DOMAIN -R owner/repo -b demo.example.org
gh variable set REGISTRY -R owner/repo -b <registry output>     # and the two GCP_* variables
```

The workflow runs on pushes to `seed-from-template` and `main`, by hand (`gh workflow run artifacts.yml`), and on version tags: `git tag -s v0.1.0 && git push origin v0.1.0` builds the APK `onesa-v0.1.0` and the images tagged `v0.1.0` and `latest`; a branch push tags them with the short commit SHA. The APK carries the live URLs, so anyone who can download the run's artifacts can read them.

CI never builds `fhir-gateway`: its plugin comes from a private fork. Push it from the laptop with `GATEWAY_PLUGIN_DIR` set: `./dev.sh push <sha> fhir-gateway`, using the SHA CI tagged so the server pulls one `IMAGE_TAG` for all four.

### 4. Server

From the laptop, `infra/gcp/server.sh` runs steps 4 and 5 over IAP, using `config.yaml` (project, zone, server, `github_repository`) and `infra/.env`:

| Command | Does |
| --- | --- |
| `server.sh setup` | clones or updates the checkout at the current branch, gives your user Docker and registry access, copies `.env` up |
| `server.sh nginx` | `./dev.sh nginx` |
| `server.sh up --taifafeki` | `./dev.sh up --pull --taifafeki` (add `--pipes` as needed) |
| `server.sh cli` | builds `trust-demo` here and ships it |
| `server.sh trust-demo reset` | runs the CLI there against `infra/.env` (any subcommand) |
| `server.sh check` | `stack/check.sh infra/.env` |
| `server.sh ssh` | a shell on the server |

The commands it runs, step by step:

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
sudo usermod -aG docker "$USER"     # once, then sign in again
gcloud auth configure-docker <region>-docker.pkg.dev    # once
./dev.sh nginx                      # vhosts and one certificate per name
./dev.sh up --pull --taifafeki      # add --pipes for the analytics pipeline and Superset
```

`--pull` is needed because the checkout has `gradlew`, which otherwise makes `up` build from source. `GATEWAY_PLUGIN_DIR` must be set but can be any path while pulling.

### Verify the services

```
infra/gcp/server.sh status
```

It lists every container's health and the server's memory, probes each public endpoint for its expected answer, and prints every certificate's expiry; it exits non-zero on any failure:

```
OK    Keycloak realm              (OpenID discovery, 200)
OK    gateway metadata            (200)
OK    gateway refuses no token    (/fhir/Patient, 401)
OK    VHL service · trust list · Medplum · Taifafeki client · web portal
cert  keycloak.onesa.demo.example.org: <expiry>
```

Run it after `up` and whenever something looks off. `server.sh check` is the deeper test once the story is seeded (step 5).

### 5. Seed and check (server)

With the wrapper, from the laptop:

```
infra/gcp/server.sh cli                   # build and ship trust-demo
infra/gcp/server.sh trust-demo reset      # seed both countries; writes the Taifafeki secrets
infra/gcp/server.sh up --taifafeki        # restart so the facility client reads them
infra/gcp/server.sh check
```

By hand, the same steps:

`trust-demo` needs only a Java 21 runtime, its install directory and the `.env`. The IG resources and the WHO example card are packed into its jar. It writes the facility client's secrets to `stack/taifafeki/secrets/`, resolved from the `.env` path as `../stack/taifafeki/secrets`, so run it inside the checkout. `stack/check.sh` needs `curl` and `jq`.

Install on a VM that predates this change: `sudo apt-get install -y openjdk-21-jre-headless jq`. New VMs get both from `startup.sh`.

Ship the CLI from the laptop:

```
./gradlew :tools:control:installDist
gcloud compute scp --recurse tools/control/build/install/control trust-demo:~/smart-trust-demo-impl/tools/control/build/install/ --project my-trust-demo --zone europe-west3-a --tunnel-through-iap
```

On the server, in `~/smart-trust-demo-impl`:

```
tools/control/build/install/control/bin/trust-demo reset --stack-env infra/.env
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

Run `trust-demo watch --stack-env infra/.env` on the server and follow [Walk the story](../README.md#walk-the-story). Logins are in `infra/.env` (`TRUSTDEMO_USER_PASSWORD`).

For a new household, `trust-demo family add --patient <id> --username <name> --stack-env infra/.env` creates the parent's wallet login through Keycloak on loopback (`KEYCLOAK_PORT`), and `--newborn <hospital number>` on `status`, `journeys`, `inspect` and `watch` follows that child.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Names resolve to addresses other than `server_ip` | the DNS records are proxied (e.g. Cloudflare's orange cloud) | set them to DNS only |
| `./dev.sh nginx` warns "No certificate for <host>" | certbot failed for that name | `sudo tail -50 /var/log/letsencrypt/letsencrypt.log` on the server; check port 80 reaches it |
| An image is missing from the registry after `./dev.sh push` | the push stopped partway | `docker push <REGISTRY>/<image>:<tag>` again |
| A laptop build cannot resolve `registry-1.docker.io` after a network change | the buildx builder kept the old DNS | `docker restart buildx_buildkit_<builder>` (`docker buildx ls`) |
| Old realm or client names after updating the repo | `.env` keeps the values of the `.env.example` it was made from | compare `infra/.env` with `.env.example` and set the changed defaults |
| `permission denied` on `docker` on the server | the user is not in the `docker` group | `server.sh setup` adds it; the next session has it |
| The facility client cannot sign in after `reset` | it started before `reset` wrote its secrets | `server.sh up --taifafeki` |

## Update

```
git tag -s v0.2.0 && git push origin v0.2.0      # CI builds the APK and vhl, issuer, taifafeki-client, web-portal as v0.2.0
infra/dev.sh push v0.2.0 fhir-gateway            # laptop: the gateway, from the private fork
# laptop: set IMAGE_TAG=v0.2.0 in infra/.env, then
infra/gcp/server.sh setup                        # pulls the code, copies .env
infra/gcp/server.sh up --taifafeki
infra/gcp/server.sh status
```

Without a tag, CI tags images with the short commit SHA; `./dev.sh push <tag>` on the laptop still builds all four. A changed CLI is shipped again as in step 5. A realm change needs a fresh Keycloak database (`./dev.sh reset` wipes all volumes, then `up` and `trust-demo reset`). An existing deployment gets the CLI's own Keycloak identity (`service-account-trust-demo-cli`) only that way, so `family add` fails with 401/403 until then.

## Tear down

```
cd infra/gcp && tofu destroy
```

This removes the VM, address, registry and network. The project is kept (`deletion_policy = "PREVENT"`); delete it in the console if you want it gone. Remove the DNS records yourself.
