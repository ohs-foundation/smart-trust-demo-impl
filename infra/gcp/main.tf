resource "google_project" "demo" {
  project_id      = local.project_id
  name            = local.project_name
  org_id          = local.org_id
  folder_id       = local.folder_id
  billing_account = local.billing_account
}

resource "google_project_service" "apis" {
  for_each = toset([
    "compute.googleapis.com",
    "artifactregistry.googleapis.com",
    "iap.googleapis.com",
    "iamcredentials.googleapis.com",
    "sts.googleapis.com",
  ])
  project            = google_project.demo.project_id
  service            = each.value
  disable_on_destroy = false
}

resource "google_artifact_registry_repository" "images" {
  project       = google_project.demo.project_id
  location      = local.region
  repository_id = local.repository
  format        = "DOCKER"
  description   = "The demo's own images: the gateway fork, the VHL service, the Taifafeki client, the web portal"
  depends_on    = [google_project_service.apis]
}

resource "google_compute_network" "demo" {
  project                 = google_project.demo.project_id
  name                    = "trust-demo"
  auto_create_subnetworks = false
  depends_on              = [google_project_service.apis]
}

resource "google_compute_subnetwork" "demo" {
  project       = google_project.demo.project_id
  name          = "trust-demo"
  region        = local.region
  network       = google_compute_network.demo.id
  ip_cidr_range = "10.10.0.0/24"
}

resource "google_compute_address" "server" {
  project    = google_project.demo.project_id
  name       = "trust-demo"
  region     = local.region
  depends_on = [google_project_service.apis]
}

resource "google_compute_firewall" "web" {
  project       = google_project.demo.project_id
  name          = "trust-demo-web"
  network       = google_compute_network.demo.id
  source_ranges = ["0.0.0.0/0"]
  target_tags   = ["trust-demo"]
  allow {
    protocol = "tcp"
    ports    = ["80", "443"]
  }
}

# SSH only through IAP TCP forwarding (Google's published range), never from the internet.
resource "google_compute_firewall" "iap_ssh" {
  project       = google_project.demo.project_id
  name          = "trust-demo-iap-ssh"
  network       = google_compute_network.demo.id
  source_ranges = ["35.235.240.0/20"]
  target_tags   = ["trust-demo"]
  allow {
    protocol = "tcp"
    ports    = ["22"]
  }
}

resource "google_service_account" "server" {
  project      = google_project.demo.project_id
  account_id   = "trust-demo-server"
  display_name = "SMART Trust demo server"
}

resource "google_artifact_registry_repository_iam_member" "server_pull" {
  project    = google_project.demo.project_id
  location   = google_artifact_registry_repository.images.location
  repository = google_artifact_registry_repository.images.repository_id
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.server.email}"
}

resource "google_project_iam_member" "server_logs" {
  for_each = toset(["roles/logging.logWriter", "roles/monitoring.metricWriter"])
  project  = google_project.demo.project_id
  role     = each.value
  member   = "serviceAccount:${google_service_account.server.email}"
}

resource "google_compute_instance" "server" {
  project      = google_project.demo.project_id
  name         = local.server_name
  zone         = local.zone
  machine_type = local.machine_type
  tags         = ["trust-demo"]

  boot_disk {
    initialize_params {
      image = "ubuntu-os-cloud/ubuntu-2404-lts-amd64"
      size  = local.disk_gb
      type  = "pd-balanced"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.demo.id
    access_config {
      nat_ip = google_compute_address.server.address
    }
  }

  service_account {
    email  = google_service_account.server.email
    scopes = ["cloud-platform"]
  }

  metadata = {
    enable-oslogin = "TRUE"
    startup-script = templatefile("${path.module}/startup.sh", {
      registry = "${local.region}-docker.pkg.dev"
    })
  }

  depends_on = [google_artifact_registry_repository_iam_member.server_pull]
}

# CI pushes images keylessly: GitHub's OIDC token for this repository alone is exchanged for the CI account.
resource "google_iam_workload_identity_pool" "github" {
  count                     = local.github_repo == "" ? 0 : 1
  project                   = google_project.demo.project_id
  workload_identity_pool_id = "trust-demo-github"
  display_name              = "GitHub Actions"
  depends_on                = [google_project_service.apis]
}

resource "google_iam_workload_identity_pool_provider" "github" {
  count                              = local.github_repo == "" ? 0 : 1
  project                            = google_project.demo.project_id
  workload_identity_pool_id          = google_iam_workload_identity_pool.github[0].workload_identity_pool_id
  workload_identity_pool_provider_id = "github"
  display_name                       = "GitHub OIDC"
  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
    "attribute.ref"        = "assertion.ref"
  }
  attribute_condition = "assertion.repository == \"${local.github_repo}\""
  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

resource "google_service_account" "ci" {
  count        = local.github_repo == "" ? 0 : 1
  project      = google_project.demo.project_id
  account_id   = "trust-demo-ci"
  display_name = "SMART Trust demo CI"
}

resource "google_artifact_registry_repository_iam_member" "ci_push" {
  count      = local.github_repo == "" ? 0 : 1
  project    = google_project.demo.project_id
  location   = google_artifact_registry_repository.images.location
  repository = google_artifact_registry_repository.images.repository_id
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.ci[0].email}"
}

resource "google_service_account_iam_member" "ci_github" {
  count              = local.github_repo == "" ? 0 : 1
  service_account_id = google_service_account.ci[0].name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github[0].name}/attribute.repository/${local.github_repo}"
}
