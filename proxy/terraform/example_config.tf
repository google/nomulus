terraform {
  backend "gcs" {
    # The name of the GCS bucket that stores the terraform.tfstate file.
    bucket = "YOUR_GCS_BUCKET"
    prefix = "terraform/state"
  }
}

module "proxy" {
  source                   = "../../modules"
  proxy_project_name       = "YOUR_PROXY_PROJECT"
  gcr_project_name         = "YOUR_GCR_PROJECT"
  proxy_domain_name        = "YOUR_PROXY_DOMAIN"
  proxy_certificate_bucket = "YOU_CERTIFICATE_BUCKET"
}

output "proxy_service_account" {
  value = module.proxy.proxy_service_account
}

output "proxy_name_servers" {
  value = module.proxy.proxy_name_servers
}

output "proxy_instance_groups" {
  value = module.proxy.proxy_instance_groups
}

output "proxy_ip_addresses" {
  value = module.proxy.proxy_ip_addresses
}
