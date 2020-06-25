resource "google_dns_managed_zone" "proxy_domain" {
  name     = "proxy-domain"
  dns_name = "${var.proxy_domain_name}."

  # This is a safeguard for the google provider update to 2.8.0.
  # cl/264641943
  # If you like, you can remove this line after the update.
  lifecycle { prevent_destroy = true }
}

module "proxy_networking" {
  source                = "./networking"
  proxy_instance_groups = local.proxy_instance_groups
  proxy_ports           = var.proxy_ports
  proxy_domain          = google_dns_managed_zone.proxy_domain.name
  proxy_domain_name     = google_dns_managed_zone.proxy_domain.dns_name
}

module "proxy_networking_canary" {
  source                = "./networking"
  proxy_instance_groups = local.proxy_instance_groups
  suffix                = "-canary"
  proxy_ports           = var.proxy_ports_canary
  proxy_domain          = google_dns_managed_zone.proxy_domain.name
  proxy_domain_name     = google_dns_managed_zone.proxy_domain.dns_name
}
