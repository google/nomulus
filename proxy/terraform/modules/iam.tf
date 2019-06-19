resource "google_service_account" "proxy_service_account" {
  account_id   = "proxy-service-account"
  display_name = "Nomulus proxy service account"
}

resource "google_project_iam_member" "gcr_storage_viewer" {
  project = "${var.gcr_project_name}"
  role    = "roles/storage.objectViewer"
  member  = "serviceAccount:${google_service_account.proxy_service_account.email}"
}

resource "google_project_iam_member" "metric_writer" {
  role   = "roles/monitoring.metricWriter"
  member = "serviceAccount:${google_service_account.proxy_service_account.email}"
}

resource "google_project_iam_member" "log_writer" {
  role   = "roles/logging.logWriter"
  member = "serviceAccount:${google_service_account.proxy_service_account.email}"
}
