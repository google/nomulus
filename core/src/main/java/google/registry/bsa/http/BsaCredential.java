package google.registry.bsa.http;

import static com.google.common.base.Preconditions.checkArgument;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.api.client.http.HttpMethods;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.FluentLogger;
import com.google.gson.Gson;
import google.registry.keyring.api.Keyring;
import google.registry.request.UrlConnectionService;
import google.registry.request.UrlConnectionUtils;
import google.registry.util.Clock;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.net.ssl.HttpsURLConnection;

public class BsaCredential {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String API_KEY_PLACEHOLDER = "{API_KEY}";

  private static final Duration AUTH_REFRESH_MARGIN = Duration.ofSeconds(30);
  public static final String ID_TOKEN = "id_token";

  private final UrlConnectionService urlConnectionService;

  private final String authUrl;
  private final String contentType;

  // "apiKey={API_KEY}&space=BSA"
  private final String authRequestBodyTemplate;

  private final Duration effectiveTokenExpiry;

  private final Keyring keyring;

  private final Clock clock;

  @Nullable private String authToken;
  private java.time.Instant lastRefreshTime;

  @Inject
  BsaCredential(
      UrlConnectionService urlConnectionService,
      String authUrl,
      String contentType,
      String authRequestBodyTemplate,
      Duration authTokenExpiry,
      Keyring keyring,
      Clock clock) {
    checkArgument(
        authRequestBodyTemplate.contains(API_KEY_PLACEHOLDER),
        "Invalid request body template. Expecting embedded pattern `%s`",
        API_KEY_PLACEHOLDER);
    checkArgument(
        !authTokenExpiry.minus(AUTH_REFRESH_MARGIN).isNegative(),
        "Auth token expiry too short. Expecting at least %s",
        AUTH_REFRESH_MARGIN);
    this.urlConnectionService = urlConnectionService;
    this.authUrl = authUrl;
    this.contentType = contentType;
    this.authRequestBodyTemplate = authRequestBodyTemplate;
    this.effectiveTokenExpiry = authTokenExpiry.minus(AUTH_REFRESH_MARGIN);
    this.keyring = keyring;
    this.clock = clock;
  }

  public String getAuthToken() throws IOException {
    ensureAuthTokenValid();
    return this.authToken;
  }

  private void ensureAuthTokenValid() throws IOException {
    Instant now = Instant.ofEpochMilli(clock.nowUtc().getMillis());
    if (authToken == null || lastRefreshTime.plus(effectiveTokenExpiry).isAfter(now)) {
      return;
    }
    synchronized (this) {
      authToken = fetchNewAuthToken();
      lastRefreshTime = now;
    }
  }

  @VisibleForTesting
  String fetchNewAuthToken() throws IOException {
    String payload = authRequestBodyTemplate.replace(API_KEY_PLACEHOLDER, keyring.getBsaApiKey());

    try {
      URL url = new URL(authUrl);
      HttpsURLConnection connection =
          (HttpsURLConnection) urlConnectionService.createConnection(url);
      connection.setRequestMethod(HttpMethods.POST);
      UrlConnectionUtils.setPayload(
          connection, payload.getBytes(StandardCharsets.UTF_8), contentType);
      int code = connection.getResponseCode();
      if (code != SC_OK) {
        throw new RuntimeException(
            "Unexpected status code: " + code + " " + connection.getResponseMessage());
      }
      String idToken =
          new Gson()
              .fromJson(
                  new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8),
                  Map.class)
              .getOrDefault("id_token", "")
              .toString();
      if (idToken.isEmpty()) {
        throw new RuntimeException("Id token not found");
      }
      return idToken;
    } catch (GeneralSecurityException e) {
      throw new RuntimeException(e);
    }
  }
}
