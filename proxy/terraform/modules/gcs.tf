resource "google_storage_bucket" "proxy_certificate" {
  name          = var.proxy_certificate_bucket
  storage_class = "MULTI_REGIONAL"
}

resource "google_storage_bucket_iam_member" "certificate_viewer" {
  bucket = google_storage_bucket.proxy_certificate.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.proxy_service_account.email}"
}

resource "google_storage_bucket_iam_member" "gcr_viewer" {
  bucket = "artifacts.${var.gcr_project_name}.appspot.com"
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.proxy_service_account.email}"
}
