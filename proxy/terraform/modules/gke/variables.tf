variable "proxy_service_account_email" {}

variable "proxy_cluster_region" {}

variable "proxy_cluster_zones" {
  type = map

  default = {
    americas = "us-east4-a"
    emea     = "europe-west4-b"
    apac     = "asia-northeast1-c"
  }
}
