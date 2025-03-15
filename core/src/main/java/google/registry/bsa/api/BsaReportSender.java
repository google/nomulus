// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bsa.api;

import static jakarta.servlet.http.HttpServletResponse.SC_ACCEPTED;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.api.client.http.HttpMethods;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.ByteStreams;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.UrlConnectionService;
import google.registry.request.UrlConnectionUtils;
import google.registry.util.Retrier;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.GeneralSecurityException;
import javax.net.ssl.HttpsURLConnection;

/**
 * Sends order processing reports to BSA.
 *
 * <p>Senders are responsible for keeping payloads at reasonable sizes.
 */
public class BsaReportSender {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final MediaType CONTENT_TYPE = MediaType.JSON_UTF_8;

  private final UrlConnectionService urlConnectionService;
  private final BsaCredential credential;
  private final String orderStatusUrl;
  private final String addUnblockableDomainsUrl;
  private final String removeUnblockableDomainsUrl;

  private final Retrier retrier;

  @Inject
  BsaReportSender(
      UrlConnectionService urlConnectionService,
      BsaCredential credential,
      @Config("bsaOrderStatusUrl") String orderStatusUrl,
      @Config("bsaAddUnblockableDomainsUrl") String addUnblockableDomainsUrl,
      @Config("bsaRemoveUnblockableDomainsUrl") String removeUnblockableDomainsUrl,
      Retrier retrier) {
    this.urlConnectionService = urlConnectionService;
    this.credential = credential;
    this.orderStatusUrl = orderStatusUrl;
    this.addUnblockableDomainsUrl = addUnblockableDomainsUrl;
    this.removeUnblockableDomainsUrl = removeUnblockableDomainsUrl;
    this.retrier = retrier;
  }

  public void sendOrderStatusReport(String payload) {
    retrier.callWithRetry(
        () -> trySendData(this.orderStatusUrl, payload),
        e -> e instanceof BsaException && ((BsaException) e).isRetriable());
  }

  public void addUnblockableDomainsUpdates(String payload) {
    retrier.callWithRetry(
        () -> trySendData(this.addUnblockableDomainsUrl, payload),
        e -> e instanceof BsaException && ((BsaException) e).isRetriable());
  }

  public void removeUnblockableDomainsUpdates(String payload) {
    retrier.callWithRetry(
        () -> trySendData(this.removeUnblockableDomainsUrl, payload),
        e -> e instanceof BsaException && ((BsaException) e).isRetriable());
  }

  Void trySendData(String urlString, String payload) {
    try {
      URL url = new URL(urlString);
      HttpsURLConnection connection =
          (HttpsURLConnection) urlConnectionService.createConnection(url);
      connection.setRequestMethod(HttpMethods.POST);
      connection.setRequestProperty("Authorization", "Bearer " + credential.getAuthToken());
      UrlConnectionUtils.setPayload(connection, payload.getBytes(UTF_8), CONTENT_TYPE.toString());
      int code = connection.getResponseCode();
      if (code != SC_OK && code != SC_ACCEPTED) {
        String errorDetails = "";
        try (InputStream errorStream = connection.getErrorStream()) {
          errorDetails = new String(ByteStreams.toByteArray(errorStream), UTF_8);
        } catch (NullPointerException e) {
          // No error message.
        } catch (Exception e) {
          errorDetails = "Failed to retrieve error message: " + e.getMessage();
        }
        // TODO(b/318404541): sanitize errorDetails to prevent log injection attack.
        throw new BsaException(
            String.format(
                "Status code: [%s], error: [%s], details: [%s]",
                code, connection.getResponseMessage(), errorDetails),
            /* retriable= */ true);
      }
      try (InputStream errorStream = connection.getInputStream()) {
        String responseMessage = new String(ByteStreams.toByteArray(errorStream), UTF_8);
        logger.atInfo().log("Received response: [%s]", responseMessage);
      } catch (Exception e) {
        logger.atInfo().withCause(e).log("Failed to retrieve response message.");
      }
      return null;
    } catch (IOException e) {
      throw new BsaException(e, /* retriable= */ true);
    } catch (GeneralSecurityException e) {
      throw new BsaException(e, /* retriable= */ false);
    }
  }
}
