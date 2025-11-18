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

package google.registry.reporting.mosapi;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.HttpUtils;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * A client for interacting with the ICANN Monitoring System API (MoSAPI).
 *
 * <p>This client handles the session lifecycle (login/logout) and provides methods to access the
 * various MoSAPI endpoints. It is designed to be reusable and can be injected where needed.
 */
@Singleton
public final class MosApiClient {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final String baseUrl;

  private final Function<String, String> usernameProvider;
  private final Function<String, String> passwordProvider;

  // API Endpoints
  private static final String LOGIN_PATH = "/login";
  private static final String LOGOUT_PATH = "/logout";
  // HTTP Headers
  private static final String HEADER_AUTHORIZATION = "Authorization";
  private static final String HEADER_CONTENT_TYPE = "Content-Type";
  private static final String HEADER_COOKIE = "Cookie";
  private static final String HEADER_SET_COOKIE = "Set-Cookie";

  // HTTP Header Prefixes and Values
  private static final String AUTH_BASIC_PREFIX = "Basic ";
  private static final String CONTENT_TYPE_JSON = "application/json";

  // Cookie Parsing
  private static final String COOKIE_ID_PREFIX = "id=";
  private static final char COOKIE_DELIMITER = ';';
  private final int RATE_LIMIT = 429;

  private final HttpClient httpClient;
  private final MosApiSessionCache mosApiSessionCache;

  /**
   * Constructs a new MosApiClient.
   *
   * @param entityType "ry" for registries or "rr" for registrars.
   * @param usernameProvider The usernameProvider for authentication.
   * @param passwordProvider The passwordProvider for authentication.
   */
  @Inject
  public MosApiClient(
      HttpClient.Builder httpClientBuilder,
      @Config("mosapiUrl") String mosapiUrl,
      @Config("entityType") String entityType,
      @Named("mosapiUsernameProvider") Function<String, String> usernameProvider,
      @Named("mosapiPasswordProvider") Function<String, String> passwordProvider,
      MosApiSessionCache mosApiSessionCache) {
    this.baseUrl = String.format("%s/%s", mosapiUrl, entityType);
    this.usernameProvider = usernameProvider;
    this.passwordProvider = passwordProvider;
    this.mosApiSessionCache = mosApiSessionCache;
    this.httpClient = httpClientBuilder.build();
  }

  /**
   * Authenticates with the MoSAPI to create a session.
   *
   * <p>A successful login stores a session cookie that is used for subsequent requests.
   *
   * @throws MosApiException if the login request fails.
   */
  public void login(String entityId) throws MosApiException {

    String loginUrl = buildUrl(entityId, LOGIN_PATH, Collections.emptyMap());
    String username = usernameProvider.apply(entityId);
    String password = passwordProvider.apply(entityId);
    String auth = username + ":" + password;
    String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));

    try {
      HttpResponse<String> response =
          HttpUtils.sendPostRequest(
              httpClient,
              loginUrl,
              ImmutableMap.of(HEADER_AUTHORIZATION, AUTH_BASIC_PREFIX + encodedAuth));

      switch (response.statusCode()) {
        case HttpURLConnection.HTTP_OK:
          Optional<String> setCookieHeader = response.headers().firstValue(HEADER_SET_COOKIE);
          if (setCookieHeader.isEmpty()) {
            throw new MosApiException(
                "Login succeeded but server did not return a Set-Cookie header.");
          }
          String cookieValue = parseCookieValue(setCookieHeader.get());
          mosApiSessionCache.store(entityId, cookieValue);
          logger.atInfo().log("MoSAPI login successful");
          break;
        case HttpURLConnection.HTTP_UNAUTHORIZED:
          throw new InvalidCredentialsException(response.body());
        case HttpURLConnection.HTTP_FORBIDDEN:
          throw new IpAddressNotAllowedException(response.body());
        case RATE_LIMIT:
          throw new RateLimitExceededException(response.body());
        default:
          throw new MosApiException(
              String.format(
                  "Login failed with unexpected status code: %d - %s",
                  response.statusCode(), response.body()));
      }
    } catch (MosApiException e) {
      throw e;
    } catch (Exception e) {
      throw new MosApiException("An error occurred during login.", e);
    }
  }

  /**
   * Logs out and terminates the current session.
   *
   * @throws MosApiException if the logout request fails.
   */
  public void logout(String entityId) throws MosApiException {
    String logoutUrl = buildUrl(entityId, LOGOUT_PATH, Collections.emptyMap());
    Optional<String> cookie = mosApiSessionCache.get(entityId);
    Map<String, String> headers =
        cookie.isPresent() ? ImmutableMap.of(HEADER_COOKIE, cookie.get()) : ImmutableMap.of();

    try {
      HttpResponse<String> response = HttpUtils.sendPostRequest(httpClient, logoutUrl, headers);

      switch (response.statusCode()) {
        case HttpURLConnection.HTTP_OK:
          logger.atInfo().log("Logout successful.");
          break;
        case HttpURLConnection.HTTP_UNAUTHORIZED:
          logger.atWarning().log(
              "Warning: %s (Session may have already expired).", response.body());
          break;
        case HttpURLConnection.HTTP_FORBIDDEN:
          throw new IpAddressNotAllowedException(response.body());
        default:
          throw new MosApiException(
              String.format(
                  "Logout failed with unexpected status code: %d - %s",
                  response.statusCode(), response.body()));
      }
    } catch (MosApiException e) {
      throw e;
    } catch (Exception e) {
      throw new MosApiException("An error occurred during logout.", e);
    } finally {
      mosApiSessionCache.clear(entityId);
      logger.atInfo().log("Cleared session cache for %s", entityId);
    }
  }

  /**
   * Executes a GET request with automatic session handling and re-login.
   *
   * @param entityId The entityId (e.g., TLD) for this request.
   * @param path The API path, e.g., "/monitoring/state".
   * @param queryParams A map of query parameters.
   * @param additionalHeaders Any custom headers for this specific request (e.g., "Accept").
   * @return The response body as a String.
   * @throws MosApiException if the request fails.
   */
  public String executeGetRequest(
      String entityId,
      String path,
      Map<String, String> queryParams,
      Map<String, String> additionalHeaders)
      throws MosApiException {

    String url = buildUrl(entityId, path, queryParams);

    BiFunction<String, String, HttpResponse<String>> requestExecutor =
        (requestUrl, cookie) -> {
          try {
            ImmutableMap.Builder<String, String> headers = ImmutableMap.builder();
            headers.put(HEADER_COOKIE, cookie);
            if (additionalHeaders != null) {
              headers.putAll(additionalHeaders);
            }
            return HttpUtils.sendGetRequest(httpClient, requestUrl, headers.build());
          } catch (IOException | InterruptedException e) {
            throw new RuntimeException(new MosApiException("HTTP GET request failed", e));
          }
        };

    HttpResponse<String> response = executeRequestWithRetry(entityId, url, requestExecutor);

    if (response.statusCode() != HttpURLConnection.HTTP_OK) {
      throw new MosApiException(
          String.format(
              "GET request to %s failed with status code: %d - %s",
              path, response.statusCode(), response.body()));
    }
    return response.body();
  }

  /**
   * Executes a POST request with automatic session handling and re-login.
   *
   * @param entityId The entityId (e.g., TLD) for this request.
   * @param path The API path, e.g., "/monitoring/incident/123/falsePositive".
   * @param body An optional request body (e.g., JSON string). Use null or empty for no body.
   * @param additionalHeaders Any custom headers for this specific request.
   * @return The response body as a String.
   * @throws MosApiException if the request fails.
   */
  public String executePostRequest(
      String entityId, String path, @Nullable String body, Map<String, String> additionalHeaders)
      throws MosApiException {

    String url = buildUrl(entityId, path, Collections.emptyMap());
    final String requestBody = Strings.nullToEmpty(body);

    // Define the request-executing lambda
    BiFunction<String, String, HttpResponse<String>> requestExecutor =
        (requestUrl, cookie) -> {
          try {
            // Build the headers map
            ImmutableMap.Builder<String, String> headers = ImmutableMap.builder();
            headers.put(HEADER_COOKIE, cookie);
            if (additionalHeaders != null) {
              headers.putAll(additionalHeaders);
            }
            if (!requestBody.isEmpty()) {
              headers.put(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON);
            }

            return HttpUtils.sendPostRequest(httpClient, requestUrl, headers.build(), requestBody);
          } catch (IOException | InterruptedException e) {
            throw new RuntimeException(new MosApiException("HTTP POST request failed", e));
          }
        };

    HttpResponse<String> response = executeRequestWithRetry(entityId, url, requestExecutor);

    if (response.statusCode() != HttpURLConnection.HTTP_OK) {
      throw new MosApiException(
          String.format(
              "POST request to %s failed with status code: %d - %s",
              path, response.statusCode(), response.body()));
    }
    return response.body();
  }

  /**
   * Executes a request function with automatic session caching and re-login on expiry.
   *
   * @param entityId The entityId for the request.
   * @param url The full URL to request.
   * @param requestExecutor A function that takes (URL, CookieString) and returns an HttpResponse.
   * @return The HttpResponse from the successful request.
   * @throws MosApiException if the request fails permanently.
   */
  private HttpResponse<String> executeRequestWithRetry(
      String entityId, String url, BiFunction<String, String, HttpResponse<String>> requestExecutor)
      throws MosApiException {

    // 1. Try with existing cookie from cache
    Optional<String> cookie = mosApiSessionCache.get(entityId);
    if (cookie.isPresent()) {
      try {
        HttpResponse<String> response = requestExecutor.apply(url, cookie.get());
        if (isSessionExpiredError(response)) {
          logger.atWarning().log("Session expired for %s. Re-logging in.", entityId);
        } else {
          return response; // Success or other non-session-expired error
        }
      } catch (RuntimeException e) {
        if (e.getCause() instanceof MosApiException) {
          throw (MosApiException) e.getCause();
        }
        throw new MosApiException("Request failed", e);
      }
    } else {
      logger.atInfo().log("No session cookie cached for %s. Logging in.", entityId);
    }

    // 2. If no cookie, or if session was expired, perform login.
    try {
      login(entityId);
    } catch (RateLimitExceededException e) {
      throw new MosApiException("Try running after some time", e);
    } catch (MosApiException e) {
      throw new MosApiException("Automatic re-login failed.", e);
    }

    // 3. Retry the original request with the new cookie
    logger.atInfo().log("Login successful. Retrying original request for %s.", entityId);
    cookie = mosApiSessionCache.get(entityId);
    if (cookie.isEmpty()) {
      throw new MosApiException("Login succeeded but failed to retrieve new session cookie.");
    }

    try {
      HttpResponse<String> response = requestExecutor.apply(url, cookie.get());
      if (isSessionExpiredError(response)) {
        throw new MosApiException(
            "Authentication failed even after re-login.",
            new InvalidCredentialsException(response.body()));
      }
      return response;
    } catch (RuntimeException e) {
      if (e.getCause() instanceof MosApiException) {
        throw (MosApiException) e.getCause();
      }
      throw new MosApiException("Request failed after re-login", e);
    }
  }

  /**
   * Parses the "id=..." cookie value from the "Set-Cookie" header.
   *
   * @param setCookieHeader The raw value of the "Set-Cookie" header.
   * @return The "id=..." part of the cookie.
   * @throws MosApiException if the "id" part cannot be found.
   */
  private String parseCookieValue(String setCookieHeader) throws MosApiException {
    for (String part : Splitter.on(COOKIE_DELIMITER).trimResults().split(setCookieHeader)) {
      if (part.startsWith(COOKIE_ID_PREFIX)) {
        return part;
      }
    }
    throw new MosApiException(
        String.format("Could not parse 'id' from Set-Cookie header: %s", setCookieHeader));
  }

  /**
   * Checks if an HTTP response indicates an expired session.
   *
   * @param response The HTTP response.
   * @return True if the response is a 401 with the specific session expired message.
   */
  private boolean isSessionExpiredError(HttpResponse<String> response) {
    return (response.statusCode() == HttpURLConnection.HTTP_UNAUTHORIZED);
  }

  /**
   * Builds the full URL for a request, including the base URL, entityId, path, and query params.
   */
  private String buildUrl(String entityId, String path, Map<String, String> queryParams) {
    String sanitizedPath = path.startsWith("/") ? path : "/" + path;
    String fullPath = "/" + entityId + sanitizedPath;

    if (queryParams == null || queryParams.isEmpty()) {
      return baseUrl + fullPath;
    }
    String queryString =
        queryParams.entrySet().stream()
            .map(
                entry ->
                    entry.getKey()
                        + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    return baseUrl + fullPath + "?" + queryString;
  }

  /** Custom exception for MoSAPI client errors. */
  public static class MosApiException extends Exception {
    public MosApiException(String message) {
      super(message);
    }

    public MosApiException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Thrown when MoSAPI returns a 401 Unauthorized error. */
  public static class InvalidCredentialsException extends MosApiException {
    public InvalidCredentialsException(String message) {
      super(message);
    }
  }

  /** Thrown when MoSAPI returns a 403 Forbidden error. */
  public static class IpAddressNotAllowedException extends MosApiException {
    public IpAddressNotAllowedException(String message) {
      super(message);
    }
  }

  /** Thrown when MoSAPI returns a 429 Too Many Requests error. */
  public static class RateLimitExceededException extends MosApiException {
    public RateLimitExceededException(String message) {
      super(message);
    }
  }
}
