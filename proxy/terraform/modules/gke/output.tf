output "proxy_instance_group" {
  value = google_container_cluster.proxy_cluster.instance_group_urls[0]
}
