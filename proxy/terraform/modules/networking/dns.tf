resource "google_dns_record_set" "proxy_epp_a_record" {
  name         = "epp${var.suffix}.${var.proxy_domain_name}"
  type         = "A"
  ttl          = 300
  managed_zone = var.proxy_domain
  rrdatas      = [google_compute_global_address.proxy_ipv4_address.address]
}

resource "google_dns_record_set" "proxy_epp_aaaa_record" {
  name         = "epp${var.suffix}.${var.proxy_domain_name}"
  type         = "AAAA"
  ttl          = 300
  managed_zone = var.proxy_domain
  rrdatas      = [google_compute_global_address.proxy_ipv6_address.address]
}

resource "google_dns_record_set" "proxy_whois_a_record" {
  name         = "whois${var.suffix}.${var.proxy_domain_name}"
  type         = "A"
  ttl          = 300
  managed_zone = var.proxy_domain
  rrdatas      = [google_compute_global_address.proxy_ipv4_address.address]
}

resource "google_dns_record_set" "proxy_whois_aaaa_record" {
  name         = "whois${var.suffix}.${var.proxy_domain_name}"
  type         = "AAAA"
  ttl          = 300
  managed_zone = var.proxy_domain
  rrdatas      = [google_compute_global_address.proxy_ipv6_address.address]
}
