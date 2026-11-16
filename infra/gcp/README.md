# infra/gcp

Provisions one Google Cloud server for the demo with OpenTofu: a project, a private Docker registry, a network with a fixed IP, and one Ubuntu VM with Docker and host nginx. Both countries run on it; in a real deployment each country has its own.

```
config.yaml ──tofu apply──▶ project ─┬─ Artifact Registry (the demo's own images)
                                     ├─ private bucket (the debug APK)
                                     ├─ static IP ◀── DNS: *.onesa, *.kesaro, *.gdhcn under base_domain
                                     └─ VM: :80 :443 open, SSH only through IAP
```

| Step | Command |
|---|---|
| Settings | `cp config.example.yaml config.yaml` and fill it in (git-ignored) |
| Provision | `tofu init`, `tofu plan`, `tofu apply` |
| DNS | point the three `dns_records` from the output at `server_ip` |
| CI access | set `github_repository: owner/repo` in `config.yaml` before `apply`; the repository's CI then pushes images keylessly |
| Sign in | the `ssh` output (IAP tunnel; OS Login uses your Google account) |

The VM's service account can only pull from the demo's registry, read the APK bucket, and sign blobs as itself (the APK's expiring links). The project keeps the provider's default `deletion_policy = "PREVENT"`, so `tofu destroy` removes the resources but refuses to delete the project. State stays local in this directory, git-ignored.

| Where | Command |
|---|---|
| Laptop | `gcloud auth configure-docker <region>-docker.pkg.dev`, `REGISTRY=<registry output>` in `.env`, then `./dev.sh push` |
| Server | the same `REGISTRY` and `IMAGE_TAG=<pushed tag or latest>` in `.env`, then `./dev.sh up` pulls instead of building |

With `github_repository` set, a workload identity pool trusts GitHub's OIDC token only when `assertion.repository == "owner/repo"`, and the `trust-demo-ci` account it impersonates can write to this registry and to the APK bucket, and nothing else. Set these repository **secrets**, never variables: a public repository's logs print variables in clear (Settings, Secrets and variables, Actions, Secrets):

| Secret | From |
|---|---|
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | output `github_workload_identity_provider` |
| `GCP_CI_SERVICE_ACCOUNT` | output `github_ci_service_account` |
| `REGISTRY` | output `registry` |
| `LIVE_BASE_DOMAIN` | `base_domain` from `config.yaml` |
| `APK_BUCKET` | output `apk_bucket` |

CI uploads the debug APK, which embeds the live URLs, to the `apk_bucket` bucket (`<project_id>-apk` unless `config.yaml` names one) instead of an Actions artifact, as `onesa-<tag or short SHA>.apk` and `onesa-latest.apk`. The bucket has uniform access and enforced public access prevention; per-build copies are deleted after 30 days. Download one with `gcloud storage cp gs://<bucket>/onesa-latest.apk .`. To hand a build to testers, `server.sh trust-demo apk-link` prints a link and QR that download it without sign-in for up to 7 days: the VM's account signs a V4 signed URL with IAM Credentials `signBlob` as itself, so no key leaves Google ([Share the APK](../../docs/deploy.md#share-the-apk)).

The server then runs `../dev.sh` as `../README.md` describes.

`server.sh` runs the server steps from the laptop over IAP (`setup`, `nginx`, `up`, `cli`, `trust-demo`, `check`, `ssh`), and `server.sh status` verifies the services: container health, every public endpoint, certificate expiry.
