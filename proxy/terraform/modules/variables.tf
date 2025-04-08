variable "proxy_project_name" {
  description = "GCP project in which the proxy runs."
}

variable "gcr_project_name" {
  description = "GCP project from which the proxy image is pulled."
}

variable "proxy_domain_name" {
  description = <<EOF
    The base domain name of the proxy, without the epp part.
    EOF
}

variable "proxy_certificate_bucket" {
  description = <<EOF
    The GCS bucket that stores the encrypted SSL certificate.  The "gs://"
    prefix should be omitted.
    EOF
}

variable "proxy_key_ring" {
  default     = "proxy-key-ring"
  description = "Cloud KMS keyring name"
}

variable "proxy_key" {
  default     = "proxy-key"
  description = "Cloud KMS key name"
}

variable "proxy_ports" {
  type        = map
  description = "Node ports exposed by the proxy."

  default = {
    health_check = 30000
    epp          = 30002
  }
}

variable "proxy_ports_canary" {
  type        = map
  description = "Node ports exposed by the canary proxy."

  default = {
    health_check = 31000
    epp          = 31002
  }
}

