# Instance groups that the load balancer forwards traffic to.
variable "proxy_instance_groups" {
  type = map
}

# Suffix (such as "-canary") added to the resource names.
variable "suffix" {
  default = ""
}

# Node ports exposed by the proxy.
variable "proxy_ports" {
  type = map
}

# DNS zone for the proxy domain.
variable "proxy_domain" {}

# domain name of the zone.
variable "proxy_domain_name" {}
