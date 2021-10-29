output "proxy_service_account" {
  value       = module.proxy.proxy_service_account
  description = "Service account the proxy runs under."
}

output "proxy_name_servers" {
  value       = module.proxy.proxy_name_servers
  description = "Name servers for the proxy's domain."
}

output "proxy_instance_groups" {
  value       = module.proxy.proxy_instance_groups
  description = <<EOF
    The name of the GCE instance group backing up the proxy's cluster.
    EOF
}

output "proxy_ip_addresses" {
  value       = module.proxy.proxy_ip_addresses
  description = "The proxy IP addresses."
}
