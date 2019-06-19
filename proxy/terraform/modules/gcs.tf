resource "google_storage_bucket" "proxy_certificate" {
  name          = "${var.proxy_certificate_bucket}"
  storage_class = "MULTI_REGIONAL"
}

resource "google_storage_bucket_iam_member" "member" {
  bucket = "${google_storage_bucket.proxy_certificate.name}"
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.proxy_service_account.email}"
}
