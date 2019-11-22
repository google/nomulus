resource "google_kms_key_ring" "proxy_key_ring" {
  name     = var.proxy_key_ring
  location = "global"
}

resource "google_kms_crypto_key" "proxy_key" {
  name     = var.proxy_key
  key_ring = google_kms_key_ring.proxy_key_ring.self_link

  lifecycle {
    # If a crypto key gets destroyed, all data encrypted with it is lost.
    prevent_destroy = true
  }
}

resource "google_kms_crypto_key_iam_member" "ssl_key_decrypter" {
  crypto_key_id = google_kms_crypto_key.proxy_key.self_link
  role          = "roles/cloudkms.cryptoKeyDecrypter"
  member        = "serviceAccount:${google_service_account.proxy_service_account.email}"
}
