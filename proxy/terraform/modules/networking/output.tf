output "proxy_ipv4_address" {
  value = google_compute_global_address.proxy_ipv4_address.address
}

output "proxy_ipv6_address" {
  value = google_compute_global_address.proxy_ipv6_address.address
}
