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

import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Function;

/**
 * A client for interacting with the ICANN Monitoring System API (MoSAPI).
 *
 * <p>This client handles the session lifecycle (login/logout) and provides methods to access the
 * various MoSAPI endpoints. It is designed to be reusable and can be injected where needed.
 */
public final class MosApiClient {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final String baseUrl;

  private final Function<String, String> usernameProvider;
  private final Function<String, String> passwordProvider;

  private final HttpClient httpClient;
  private final CookieManager cookieManager;

  private boolean isLoggedIn = false;

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
      @Named("mosapiPasswordProvider") Function<String, String> passwordProvider) {
    this.baseUrl = String.format("%s/%s", mosapiUrl, entityType);
    this.usernameProvider = usernameProvider;
    this.passwordProvider = passwordProvider;
    this.cookieManager = new CookieManager();
    this.cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);

    // Build the final HttpClient using the injected builder and our session-specific
    // CookieManager.
    this.httpClient = httpClientBuilder.cookieHandler(cookieManager).build();
  }

  /**
   * Authenticates with the MoSAPI to create a session.
   *
   * <p>A successful login stores a session cookie that is used for subsequent requests.
   *
   * @throws MosApiException if the login request fails.
   */
  public void login(String entityId) throws MosApiException {

    String loginUrl = baseUrl + "/" + entityId + "/login";
    String username = usernameProvider.apply(entityId);
    String password = passwordProvider.apply(entityId);
    String auth = username + ":" + password;
    String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(loginUrl))
            .header("Authorization", "Basic " + encodedAuth)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      switch (response.statusCode()) {
        case 200:
          isLoggedIn = true;
          logger.atInfo().log("MoSAPI login successful");
          break;
        case 401:
          throw new InvalidCredentialsException(response.body());
        case 403:
          throw new IpAddressNotAllowedException(response.body());
        case 429:
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
    String logoutUrl = baseUrl + "/" + entityId + "/logout";
    if (!isLoggedIn) {
      return; // Already logged out.
    }
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(logoutUrl))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      switch (response.statusCode()) {
        case 200:
          logger.atInfo().log("Logout successful.");
          break;
        case 401:
          logger.atWarning().log(
              "Warning: %s (Session may have already expired).", response.body());
          break;
        case 403:
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
      isLoggedIn = false;
      cookieManager.getCookieStore().removeAll(); // Clear local cookies.
    }
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
