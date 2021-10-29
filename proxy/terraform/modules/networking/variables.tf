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

variable "public_web_whois" {
  type        = number
  description = <<EOF
    Set to 1 if the whois HTTP ports are external, 0 if not.  This is necessary
    because our test projects are configured with
    constraints/compute.restrictLoadBalancerCreationForTypes, which prohibits
    forwarding external HTTP(s) connections.
    EOF
}
