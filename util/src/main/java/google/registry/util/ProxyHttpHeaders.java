// Copyright 2021 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.util;

import com.google.common.net.HttpHeaders;

/** Utility class of HTTP header names used for HTTP calls between Nomulus and the proxy. */
public final class ProxyHttpHeaders {

  /** HTTP header name used to pass the certificate hash from the proxy to Nomulus. */
  public static final String CERTIFICATE_HASH = "X-SSL-Certificate";

  /**
   * HTTP header name used to pass whether the client TLS certificate chain validated against the
   * trusted root CAs ("true" or "false").
   */
  public static final String CERTIFICATE_CHAIN_VALIDATED = "X-SSL-Certificate-Chain-Validated";

  /**
   * HTTP header name used to pass the comma-separated, canonicalized, deduplicated DNS hostnames
   * (excluding wildcard names) extracted from a client TLS certificate's Subject Alternative Names
   * (or Subject CN when no SAN extension is present). {@code SslServerInitializer} populates the
   * channel attribute only when chain validation succeeds, and the EPP handlers format, truncate on
   * whole-hostname boundaries to at most {@link X509Utils#MAX_HOSTNAMES_HEADER_BYTES} bytes, and
   * emit this header when non-empty. Consumers must still verify {@link
   * #CERTIFICATE_CHAIN_VALIDATED} is {@code "true"} before trusting this header.
   */
  public static final String CERTIFICATE_HOSTNAMES = "X-SSL-Certificate-Hostnames";

  /**
   * HTTP header name passed from Nomulus to proxy to indicate that an EPP session should be closed.
   */
  public static final String EPP_SESSION = "Epp-Session";

  /** HTTP header name used to pass the client IP address from the proxy to Nomulus. */
  public static final String IP_ADDRESS = "Nomulus-Client-Address";

  /** HTTP header name used to pass the Registrar Id from the proxy to Nomulus. */
  public static final String REGISTRAR_ID = "Nomulus-Registrar-Id";

  /** Fallback HTTP header name used to pass the client IP address from the proxy to Nomulus. */
  public static final String FALLBACK_IP_ADDRESS = HttpHeaders.X_FORWARDED_FOR;

  /** HTTP header name used to pass the authenticated Registrar Id from Nomulus to GKE. */
  public static final String LOGGED_IN_REGISTRAR = "Nomulus-Logged-In-Registrar";

  private ProxyHttpHeaders() {}
}
