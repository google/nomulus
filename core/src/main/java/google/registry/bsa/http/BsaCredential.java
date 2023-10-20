package google.registry.bsa.http;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.gson.Gson;
import google.registry.privileges.secretmanager.SecretManagerClient;
import google.registry.util.Clock;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;

public class BsaCredential {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String API_KEY_PLACEHOLDER = "{API_KEY}";

  private static final Duration AUTH_REFRESH_MARGIN = Duration.ofSeconds(30);
  public static final String ID_TOKEN = "id_token";

  private final HttpTransport httpTransport;

  private final String authUrl;
  private final ImmutableMap<String, String> httpHeaders;
  private final String authRequestBodyTemplate;

  private final Duration effectiveTokenExpiry;

  private final SecretManagerClient secretManagerClient;

  private final Clock clock;

  @Nullable private String authToken;
  private java.time.Instant lastRefreshTime;

  @Inject
  BsaCredential(
      HttpTransport httpTransport,
      String authUrl,
      ImmutableMap<String, String> httpHeaders,
      String authRequestBodyTemplate,
      Duration authTokenExpiry,
      SecretManagerClient secretManagerClient,
      Clock clock) {
    checkArgument(
        authRequestBodyTemplate.contains(API_KEY_PLACEHOLDER),
        "Invalid request body template. Expecting embedded pattern `%s`",
        API_KEY_PLACEHOLDER);
    checkArgument(
        !authTokenExpiry.minus(AUTH_REFRESH_MARGIN).isNegative(),
        "Auth token expiry too short. Expecting at least %s",
        AUTH_REFRESH_MARGIN);
    this.httpTransport = httpTransport;
    this.authUrl = authUrl;
    this.httpHeaders = httpHeaders;
    this.authRequestBodyTemplate = authRequestBodyTemplate;
    this.effectiveTokenExpiry = authTokenExpiry.minus(AUTH_REFRESH_MARGIN);
    this.secretManagerClient = secretManagerClient;
    this.clock = clock;
  }

  String getAuthToken() {
    ensureAuthTokenValid();
    return this.authToken;
  }

  private void ensureAuthTokenValid() {
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
  String fetchNewAuthToken() {
    GenericUrl url = new GenericUrl(authUrl);
    String payload = authRequestBodyTemplate.replace(API_KEY_PLACEHOLDER, "");
    HttpResponse response = null;

    try {
      HttpRequest request =
          httpTransport
              .createRequestFactory()
              .buildPostRequest(
                  url,
                  new ByteArrayContent(
                      PLAIN_TEXT_UTF_8.toString(), payload.getBytes(StandardCharsets.UTF_8)));
      HttpHeaders requestHeaders = request.getHeaders();
      httpHeaders.forEach(requestHeaders::set);
      response = request.execute();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    if (response == null) {
      throw new RuntimeException("Received null response.");
    }

    try {

      if (response.getStatusCode() != SC_OK) {
        throw new RuntimeException("Expecting SC_OK, got " + response.getStatusCode());
      }
      String contentType = response.getContentType();
      if (!contentType.contains("application/json")) {
        logger.atWarning().log("Expecting json content, got " + contentType);
      }

      Map<String, String> content =
          new Gson().fromJson(new InputStreamReader(response.getContent()), Map.class);
      Verify.verify(content.containsKey(ID_TOKEN), "Response missing field %s", ID_TOKEN);
      return content.get(ID_TOKEN);
    } catch (IOException e) {
      throw new RuntimeException("Failed to retrieve content.", e);
    } finally {
      try {
        response.disconnect();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
