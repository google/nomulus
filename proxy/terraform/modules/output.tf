output "proxy_name_servers" {
  value = google_dns_managed_zone.proxy_domain.name_servers
}

output "proxy_instance_groups" {
  value = local.proxy_instance_groups
}

output "proxy_service_account" {
  value = {
    email     = google_service_account.proxy_service_account.email
    client_id = google_service_account.proxy_service_account.unique_id
  }
}

output "proxy_ip_addresses" {
  value = {
    ipv4        = module.proxy_networking.proxy_ipv4_address
    ipv6        = module.proxy_networking.proxy_ipv6_address
    ipv4_canary = module.proxy_networking_canary.proxy_ipv4_address
    ipv6_canary = module.proxy_networking_canary.proxy_ipv6_address
  }
}
