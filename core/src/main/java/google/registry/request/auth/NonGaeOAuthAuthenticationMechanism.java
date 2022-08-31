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

package google.registry.request.auth;

import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpStatusCodes;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.ByteStreams;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.console.User;
import google.registry.model.console.UserDao;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;

/**
 * OAuth2 authentication mechanism that uses Google OAuth2, not GAE.
 *
 * <p>This authentication mechanism should be used in situations where we require automated OAuth2
 * authentication. The most important/clear usage of this should be in the Nomulus command-line
 * tool, which uses the installed-code flow to memoize an access token.
 *
 * <p>Note: this is dependent on the client including an OAuth2 access code in the headers of the
 * request, <b>not</b> an ID token. This access token should be created by e.g. the {@link
 * google.registry.tools.LoginCommand}. JWT ID tokens may be created and used by other means (e.g.
 * the GCP Identity-Aware Proxy) but those will use an alternate authentication mechanism.
 */
public class NonGaeOAuthAuthenticationMechanism implements AuthenticationMechanism {

  private static final String BEARER_PREFIX = "Bearer ";
  private static final String TOKEN_INFO_URL = "https://oauth2.googleapis.com/tokeninfo";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** HttpTransport used to contact Google OAuth. */
  private final NetHttpTransport httpTransport;

  /** The OAuth scopes which must all be present for authentication to succeed. */
  private final ImmutableSet<String> requiredOauthScopes;

  /** The only OAuth client IDs allowed for authentication. */
  private final ImmutableSet<String> allowedOauthClientIds;

  @Inject
  public NonGaeOAuthAuthenticationMechanism(
      NetHttpTransport httpTransport,
      @Config("requiredOauthScopes") ImmutableSet<String> requiredOauthScopes,
      @Config("allowedOauthClientIds") ImmutableSet<String> allowedOauthClientIds) {
    this.httpTransport = httpTransport;
    this.requiredOauthScopes = requiredOauthScopes;
    this.allowedOauthClientIds = allowedOauthClientIds;
  }

  @Override
  public AuthResult authenticate(HttpServletRequest request) {
    // Only accept Authorization headers in Bearer form.
    String header = request.getHeader(AUTHORIZATION);
    if ((header == null) || !header.startsWith(BEARER_PREFIX)) {
      if (header != null) {
        logger.atInfo().log("Invalid authorization header.");
      }
      return AuthResult.NOT_AUTHENTICATED;
    }
    String rawAccessToken = header.substring(BEARER_PREFIX.length());
    GenericUrl tokenInfoUrl = new GenericUrl(TOKEN_INFO_URL).set("access_token", rawAccessToken);
    JsonObject responseJson;
    try {
      HttpRequest tokenInfoRequest =
          httpTransport.createRequestFactory().buildGetRequest(tokenInfoUrl);
      HttpResponse response = tokenInfoRequest.execute();
      if (response.getStatusCode() == HttpStatusCodes.STATUS_CODE_BAD_REQUEST) {
        logger.atInfo().log("Invalid or expired token");
        return AuthResult.NOT_AUTHENTICATED;
      } else if (response.getStatusCode() == HttpStatusCodes.STATUS_CODE_OK) {
        responseJson =
            JsonParser.parseString(
                    new String(ByteStreams.toByteArray(response.getContent()), UTF_8))
                .getAsJsonObject();
      } else {
        logger.atInfo().log("Unexpected token-check status code %d", response.getStatusCode());
        return AuthResult.NOT_AUTHENTICATED;
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    String oauthClientId = responseJson.get("aud").getAsString();
    // Make sure that the client ID matches, to avoid a confused deputy attack; see:
    // http://stackoverflow.com/a/17439317/1179226
    if (!allowedOauthClientIds.contains(oauthClientId)) {
      logger.atInfo().log("OAuth audience %s is not allowed", oauthClientId);
      return AuthResult.NOT_AUTHENTICATED;
    }
    List<String> providedScopes =
        Splitter.on(' ').splitToList(responseJson.get("scope").getAsString());
    if (!providedScopes.containsAll(requiredOauthScopes)) {
      logger.atInfo().log("Provided scopes %s didn't contain required scopes", providedScopes);
      return AuthResult.NOT_AUTHENTICATED;
    }
    String emailAddress = responseJson.get("email").getAsString();
    Optional<User> maybeUser = UserDao.loadUser(emailAddress);
    if (!maybeUser.isPresent()) {
      logger.atInfo().log("No user with email address %s", emailAddress);
      return AuthResult.NOT_AUTHENTICATED;
    }
    return AuthResult.create(AuthLevel.USER, UserAuthInfo.create(maybeUser.get()));
  }
}
