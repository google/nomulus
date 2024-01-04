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

import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.api.client.http.HttpMethods;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.ByteStreams;
import com.google.gson.Gson;
import google.registry.config.RegistryConfig.Config;
import google.registry.keyring.api.Keyring;
import google.registry.request.UrlConnectionService;
import google.registry.request.UrlConnectionUtils;
import google.registry.util.Clock;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.net.ssl.HttpsURLConnection;
import org.joda.time.Duration;
import org.joda.time.Instant;

/** Self-refreshing credential for accessing the BSA API. */
public class BsaCredential {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String CONTENT_TYPE = "application/x-www-form-urlencoded";
  private static final String AUTH_REQ_BODY_TEMPLATE = "apiKey=%s&space=BSA";
  public static final String ID_TOKEN = "id_token";

  private final UrlConnectionService urlConnectionService;

  private final String authUrl;

  private final Duration authTokenExpiry;

  private final Keyring keyring;

  private final Clock clock;

  @Nullable private String authToken;
  private Instant lastRefreshTime;

  @Inject
  BsaCredential(
      UrlConnectionService urlConnectionService,
      @Config("bsaAuthUrl") String authUrl,
      @Config("bsaAuthTokenExpiry") Duration authTokenExpiry,
      Keyring keyring,
      Clock clock) {
    this.urlConnectionService = urlConnectionService;
    this.authUrl = authUrl;
    this.authTokenExpiry = authTokenExpiry;
    this.keyring = keyring;
    this.clock = clock;
  }

  /**
   * Returns the auth token for accessing the BSA API.
   *
   * <p>This method refreshes the token if it is expired.
   */
  public String getAuthToken() {
    try {
      ensureAuthTokenValid();
    } catch (IOException e) {
      throw new BsaException(e, /* retriable= */ true);
    } catch (GeneralSecurityException e) {
      throw new BsaException(e, /* retriable= */ false);
    }
    return this.authToken;
  }

  private void ensureAuthTokenValid() throws IOException, GeneralSecurityException {
    Instant now = Instant.ofEpochMilli(clock.nowUtc().getMillis());
    if (authToken != null && lastRefreshTime.plus(authTokenExpiry).isAfter(now)) {
      logger.atInfo().log("AuthToken still valid, reusing.");
      return;
    }
    synchronized (this) {
      authToken = fetchNewAuthToken();
      lastRefreshTime = now;
      logger.atInfo().log("AuthToken refreshed at %s.", now);
    }
  }

  @VisibleForTesting
  String fetchNewAuthToken() throws IOException, GeneralSecurityException {
    String payload = String.format(AUTH_REQ_BODY_TEMPLATE, keyring.getBsaApiKey());
    URL url = new URL(authUrl);
    HttpsURLConnection connection = null;
    try {
      connection = (HttpsURLConnection) urlConnectionService.createConnection(url);
      connection.setRequestMethod(HttpMethods.POST);
      UrlConnectionUtils.setPayload(connection, payload.getBytes(UTF_8), CONTENT_TYPE);
      int code = connection.getResponseCode();
      if (code != SC_OK) {
        String errorDetails = "";
        try (InputStream errorStream = connection.getErrorStream()) {
          errorDetails = new String(ByteStreams.toByteArray(errorStream), UTF_8);
        } catch (NullPointerException e) {
          // No error message.
        } catch (Exception e) {
          errorDetails = "Failed to retrieve error message: " + e.getMessage();
        }
        throw new BsaException(
            String.format(
                "Status code: [%s], error: [%s], details: [%s]",
                code, connection.getResponseMessage(), errorDetails),
            /* retriable= */ true);
      }

      try (Reader payloadReader = new InputStreamReader(connection.getInputStream(), UTF_8)) {
        @SuppressWarnings("unchecked")
        String idToken =
            new Gson().fromJson(payloadReader, Map.class).getOrDefault(ID_TOKEN, "").toString();
        if (idToken.isEmpty()) {
          throw new BsaException("Response missing ID token", /* retriable= */ false);
        }
        return idToken;
      }
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }
}
