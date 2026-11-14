# infra/gcp

Provisions one Google Cloud server for the demo with OpenTofu: a project, a private Docker registry, a network with a fixed IP, and one Ubuntu VM with Docker and host nginx. Both countries run on it; in a real deployment each country has its own.

```
config.yaml ──tofu apply──▶ project ─┬─ Artifact Registry (the demo's own images)
                                     ├─ static IP ◀── DNS: *.onesa, *.kesaro, *.gdhcn under base_domain
                                     └─ VM: :80 :443 open, SSH only through IAP
```

| Step | Command |
|---|---|
| Settings | `cp config.example.yaml config.yaml` and fill it in (git-ignored) |
| Provision | `tofu init`, `tofu plan`, `tofu apply` |
| DNS | point the three `dns_records` from the output at `server_ip` |
| Sign in | the `ssh` output (IAP tunnel; OS Login uses your Google account) |

The VM's service account can only pull from the demo's registry. The project keeps the provider's default `deletion_policy = "PREVENT"`, so `tofu destroy` removes the resources but refuses to delete the project. State stays local in this directory, git-ignored.

The server then runs `../dev.sh` as `../README.md` describes.
