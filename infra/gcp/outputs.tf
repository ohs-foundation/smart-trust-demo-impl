output "server_ip" {
  value = google_compute_address.server.address
}

output "registry" {
  value = "${local.region}-docker.pkg.dev/${google_project.demo.project_id}/${google_artifact_registry_repository.images.repository_id}"
}

output "ssh" {
  value = "gcloud compute ssh ${google_compute_instance.server.name} --project ${google_project.demo.project_id} --zone ${local.zone} --tunnel-through-iap"
}

output "dns_records" {
  value = [for country in ["onesa", "kesaro", "gdhcn"] : "*.${country}.${local.base_domain}  A  ${google_compute_address.server.address}"]
}

output "github_workload_identity_provider" {
  value       = one(google_iam_workload_identity_pool_provider.github[*].name)
  description = "GCP_WORKLOAD_IDENTITY_PROVIDER; null until github_repository is set"
}

output "github_ci_service_account" {
  value       = one(google_service_account.ci[*].email)
  description = "GCP_CI_SERVICE_ACCOUNT; null until github_repository is set"
}
