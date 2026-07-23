// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.batch;

import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.UrlConnectionService;
import google.registry.request.UrlConnectionUtils;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import javax.net.ssl.HttpsURLConnection;

/**
 * Action that executes a canned script specified by the caller.
 *
 * <p>This class provides a hook for invoking hard-coded methods. The main use case is to verify in
 * Sandbox and Production environments new features that depend on environment-specific
 * configurations.
 *
 * <p>The URL is validated to prevent server-side request forgery (SSRF). Only HTTPS URLs pointing
 * to public (non-private, non-loopback, non-link-local) IP addresses are allowed.
 *
 * <p>This action can be invoked using the Nomulus CLI command: {@code nomulus -e ${env} curl
 * --service BACKEND -X POST -u '/_dr/task/executeCannedScript?url=https://example.com/path'}
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/executeCannedScript",
    method = {POST, GET},
    automaticallyPrintOk = true,
    auth = Auth.AUTH_ADMIN)
public class CannedScriptExecutionAction implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final ImmutableSet<String> BLOCKED_HOSTS =
      ImmutableSet.of(
          "localhost",
          "metadata",
          "metadata.google.internal",
          "metadata.google.internal.",
          "kubernetes",
          "kubernetes.default",
          "kubernetes.default.svc",
          "kubernetes.default.svc.cluster.local",
          "kubernetes.default.svc.cluster.local.");

  @Inject UrlConnectionService urlConnectionService;
  @Inject Response response;

  @Inject
  @Parameter("url")
  String url;

  @Inject
  CannedScriptExecutionAction() {}

  @Override
  public void run() {
    Integer responseCode = null;
    String responseContent = null;
    try {
      logger.atInfo().log("Connecting to: %s", url);
      URL parsedUrl = new URL(url);
      validateUrl(parsedUrl);
      HttpsURLConnection connection =
          (HttpsURLConnection) urlConnectionService.createConnection(parsedUrl);
      responseCode = connection.getResponseCode();
      logger.atInfo().log("Code: %d", responseCode);
      logger.atInfo().log("Headers: %s", connection.getHeaderFields());
      responseContent = new String(UrlConnectionUtils.getResponseBytes(connection), UTF_8);
      logger.atInfo().log("Response: %s", responseContent);
    } catch (SecurityException e) {
      logger.atWarning().withCause(e).log("URL validation failed for: %s", url);
      response.setStatus(400);
      response.setPayload("Invalid URL: " + e.getMessage());
    } catch (Exception e) {
      logger.atWarning().withCause(e).log("Connection to %s failed", url);
      throw new RuntimeException(e);
    } finally {
      if (responseCode != null) {
        response.setStatus(responseCode);
      }
      if (responseContent != null) {
        response.setPayload(responseContent);
      }
    }
  }

  private void validateUrl(URL url) {
    if (!"https".equals(url.getProtocol())) {
      throw new SecurityException("Only HTTPS URLs are allowed");
    }

    String host = url.getHost();
    if (host == null || host.isEmpty()) {
      throw new SecurityException("URL must have a host");
    }

    String lowerHost = host.toLowerCase();
    if (BLOCKED_HOSTS.contains(lowerHost)) {
      throw new SecurityException(
          "Connections to internal hostnames are not allowed: " + host);
    }

    InetAddress address;
    try {
      address = InetAddress.getByName(host);
    } catch (UnknownHostException e) {
      throw new SecurityException("Could not resolve host: " + host, e);
    }

    if (address.isLoopbackAddress()) {
      throw new SecurityException("Connections to loopback addresses are not allowed");
    }
    if (address.isSiteLocalAddress()) {
      throw new SecurityException("Connections to private (site-local) addresses are not allowed");
    }
    if (address.isLinkLocalAddress()) {
      throw new SecurityException("Connections to link-local addresses are not allowed");
    }
    if (address instanceof Inet6Address
        && ((Inet6Address) address).isIPv4CompatibleAddress()) {
      throw new SecurityException(
          "Connections to IPv4-compatible IPv6 addresses are not allowed");
    }
    // Block the unspecified address (0.0.0.0 or ::)
    byte[] addrBytes = address.getAddress();
    boolean allZero = true;
    for (byte b : addrBytes) {
      if (b != 0) {
        allZero = false;
        break;
      }
    }
    if (allZero) {
      throw new SecurityException("Connections to the unspecified address are not allowed");
    }
  }
}
