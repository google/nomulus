variable "proxy_instance_groups" {
  type        = map
  description = "Instance groups that the load balancer forwards traffic to."
}

variable "suffix" {
  default     = ""
  description = "Suffix (such as '-canary') added to the resource names."
}

variable "proxy_ports" {
  type        = map
  description = "Node ports exposed by the proxy."
}

variable "proxy_domain" {
  description = "DNS zone for the proxy domain."
}

variable "proxy_domain_name" {
  description = "Domain name of the zone."
}
