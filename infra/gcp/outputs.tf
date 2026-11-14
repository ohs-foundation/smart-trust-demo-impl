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
