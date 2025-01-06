// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
   * HTTP header name passed from Nomulus to proxy to indicate that an EPP session should be closed.
   */
  public static final String EPP_SESSION = "Epp-Session";

  /** HTTP header name used to pass the client IP address from the proxy to Nomulus. */
  public static final String IP_ADDRESS = "Nomulus-Client-Address";

  /**
   * Fallback HTTP header name used to pass the client IP address from the proxy to Nomulus.
   *
   * <p>Note that Java 17's servlet implementation (at least on App Engine) injects some seemingly
   * unrelated addresses into this header. We only use this as a fallback so the proxy can
   * transition to use the above header that should not be interfered with.
   */
  public static final String FALLBACK_IP_ADDRESS = HttpHeaders.X_FORWARDED_FOR;

  private ProxyHttpHeaders() {}
}
