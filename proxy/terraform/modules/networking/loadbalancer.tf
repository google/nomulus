resource "google_compute_global_address" "proxy_ipv4_address" {
  name       = "proxy-ipv4-address${var.suffix}"
  ip_version = "IPV4"
}

resource "google_compute_global_address" "proxy_ipv6_address" {
  name       = "proxy-ipv6-address${var.suffix}"
  ip_version = "IPV6"
}

resource "google_compute_firewall" "proxy_firewall" {
  name    = "proxy-firewall${var.suffix}"
  network = "default"

  allow {
    protocol = "tcp"

    ports = [
      var.proxy_ports["epp"],
      var.proxy_ports["whois"],
      var.proxy_ports["health_check"],
      var.proxy_ports["http-whois"],
      var.proxy_ports["https-whois"],
    ]
  }

  source_ranges = [
    "130.211.0.0/22",
    "35.191.0.0/16",
  ]

  target_tags = [
    "proxy-cluster",
  ]
}

resource "google_compute_health_check" "proxy_health_check" {
  name = "proxy-health-check${var.suffix}"

  tcp_health_check {
    port     = var.proxy_ports["health_check"]
    request  = "HEALTH_CHECK_REQUEST"
    response = "HEALTH_CHECK_RESPONSE"
  }
}

resource "google_compute_health_check" "proxy_http_health_check" {
  name = "proxy-http-health-check${var.suffix}"

  http_health_check {
    host         = "health-check.invalid"
    port         = var.proxy_ports["http-whois"]
    request_path = "/"
  }
}

resource "google_compute_url_map" "proxy_url_map" {
  name            = "proxy-url-map${var.suffix}"
  default_service = google_compute_backend_service.http_whois_backend_service.self_link
}

resource "google_compute_backend_service" "epp_backend_service" {
  name        = "epp-backend-service${var.suffix}"
  protocol    = "TCP"
  timeout_sec = 3600
  port_name   = "epp${var.suffix}"

  backend {
    group = var.proxy_instance_groups["americas"]
  }

  backend {
    group = var.proxy_instance_groups["emea"]
  }

  backend {
    group = var.proxy_instance_groups["apac"]
  }

  health_checks = [
    google_compute_health_check.proxy_health_check.self_link,
  ]
}

resource "google_compute_backend_service" "whois_backend_service" {
  name        = "whois-backend-service${var.suffix}"
  protocol    = "TCP"
  timeout_sec = 60
  port_name   = "whois${var.suffix}"

  backend {
    group = var.proxy_instance_groups["americas"]
  }

  backend {
    group = var.proxy_instance_groups["emea"]
  }

  backend {
    group = var.proxy_instance_groups["apac"]
  }

  health_checks = [
    google_compute_health_check.proxy_health_check.self_link,
  ]
}

resource "google_compute_backend_service" "https_whois_backend_service" {
  name        = "https-whois-backend-service${var.suffix}"
  protocol    = "TCP"
  timeout_sec = 60
  port_name   = "https-whois${var.suffix}"

  backend {
    group = var.proxy_instance_groups["americas"]
  }

  backend {
    group = var.proxy_instance_groups["emea"]
  }

  backend {
    group = var.proxy_instance_groups["apac"]
  }

  health_checks = [
    google_compute_health_check.proxy_health_check.self_link,
  ]
}

resource "google_compute_backend_service" "http_whois_backend_service" {
  name        = "http-whois-backend-service${var.suffix}"
  protocol    = "HTTP"
  timeout_sec = 60
  port_name   = "http-whois${var.suffix}"

  backend {
    group = var.proxy_instance_groups["americas"]
  }

  backend {
    group = var.proxy_instance_groups["emea"]
  }

  backend {
    group = var.proxy_instance_groups["apac"]
  }

  health_checks = [
    google_compute_health_check.proxy_http_health_check.self_link,
  ]
}

resource "google_compute_target_tcp_proxy" "epp_tcp_proxy" {
  name            = "epp-tcp-proxy${var.suffix}"
  proxy_header    = "PROXY_V1"
  backend_service = google_compute_backend_service.epp_backend_service.self_link
}

resource "google_compute_target_tcp_proxy" "whois_tcp_proxy" {
  name            = "whois-tcp-proxy${var.suffix}"
  proxy_header    = "PROXY_V1"
  backend_service = google_compute_backend_service.whois_backend_service.self_link
}

resource "google_compute_target_tcp_proxy" "https_whois_tcp_proxy" {
  name            = "https-whois-tcp-proxy${var.suffix}"
  backend_service = google_compute_backend_service.https_whois_backend_service.self_link
}

resource "google_compute_target_http_proxy" "http_whois_http_proxy" {
  name    = "http-whois-tcp-proxy${var.suffix}"
  url_map = google_compute_url_map.proxy_url_map.self_link
}

resource "google_compute_global_forwarding_rule" "epp_ipv4_forwarding_rule" {
  name       = "epp-ipv4-forwarding-rule${var.suffix}"
  ip_address = google_compute_global_address.proxy_ipv4_address.address
  target     = google_compute_target_tcp_proxy.epp_tcp_proxy.self_link
  port_range = "700"
}

resource "google_compute_global_forwarding_rule" "epp_ipv6_forwarding_rule" {
  name       = "epp-ipv6-forwarding-rule${var.suffix}"
  ip_address = google_compute_global_address.proxy_ipv6_address.address
  target     = google_compute_target_tcp_proxy.epp_tcp_proxy.self_link
  port_range = "700"
}

resource "google_compute_global_forwarding_rule" "whois_ipv4_forwarding_rule" {
  name       = "whois-ipv4-forwarding-rule${var.suffix}"
  ip_address = google_compute_global_address.proxy_ipv4_address.address
  target     = google_compute_target_tcp_proxy.whois_tcp_proxy.self_link
  port_range = "43"
}

resource "google_compute_global_forwarding_rule" "whois_ipv6_forwarding_rule" {
  name       = "whois-ipv6-forwarding-rule${var.suffix}"
  ip_address = google_compute_global_address.proxy_ipv6_address.address
  target     = google_compute_target_tcp_proxy.whois_tcp_proxy.self_link
  port_range = "43"
}

resource "google_compute_global_forwarding_rule" "https_whois_ipv4_forwarding_rule" {
  name       = "https-whois-ipv4-forwarding-rule${var.suffix}"
  ip_address = google_compute_global_address.proxy_ipv4_address.address
  target     = google_compute_target_tcp_proxy.https_whois_tcp_proxy.self_link
  port_range = "443"
  count      = var.public_web_whois
}

resource "google_compute_global_forwarding_rule" "https_whois_ipv6_forwarding_rule" {
  name       = "https-whois-ipv6-forwarding-rule${var.suffix}"
  ip_address = google_compute_global_address.proxy_ipv6_address.address
  target     = google_compute_target_tcp_proxy.https_whois_tcp_proxy.self_link
  port_range = "443"
  count      = var.public_web_whois
}

resource "google_compute_global_forwarding_rule" "http_whois_ipv4_forwarding_rule" {
  name       = "http-whois-ipv4-forwarding-rule${var.suffix}"
  ip_address = google_compute_global_address.proxy_ipv4_address.address
  target     = google_compute_target_http_proxy.http_whois_http_proxy.self_link
  port_range = "80"
  count      = var.public_web_whois
}

resource "google_compute_global_forwarding_rule" "http_whois_ipv6_forwarding_rule" {
  name       = "http-whois-ipv6-forwarding-rule${var.suffix}"
  ip_address = google_compute_global_address.proxy_ipv6_address.address
  target     = google_compute_target_http_proxy.http_whois_http_proxy.self_link
  port_range = "80"
  count      = var.public_web_whois
}
