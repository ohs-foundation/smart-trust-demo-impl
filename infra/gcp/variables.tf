variable "config_file" {
  type        = string
  default     = "config.yaml"
  description = "The deployment's settings; see config.example.yaml"
}

locals {
  config = yamldecode(file(var.config_file))

  project_id      = local.config.project_id
  project_name    = try(local.config.project_name, local.config.project_id)
  org_id          = try(local.config.org_id, null)
  folder_id       = try(local.config.folder_id, null)
  billing_account = local.config.billing_account
  region          = local.config.region
  zone            = local.config.zone
  machine_type    = local.config.machine_type
  disk_gb         = local.config.disk_gb
  base_domain     = local.config.base_domain
  repository      = try(local.config.repository, "trust-demo")
  server_name     = try(local.config.server_name, "trust-demo")
  github_repo     = try(local.config.github_repository, "")
}
