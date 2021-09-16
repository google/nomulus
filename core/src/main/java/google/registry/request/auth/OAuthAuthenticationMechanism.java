// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.request.auth.AuthLevel.NONE;
import static google.registry.request.auth.AuthLevel.USER;

import com.google.appengine.api.oauth.OAuthRequestException;
import com.google.appengine.api.oauth.OAuthService;
import com.google.appengine.api.oauth.OAuthServiceFailureException;
import com.google.appengine.api.users.User;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;

/**
 * OAuth authentication mechanism, using the OAuthService interface.
 *
 * Only OAuth version 2 is supported.
 */
public class OAuthAuthenticationMechanism implements AuthenticationMechanism {

  private static final String BEARER_PREFIX = "Bearer ";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final OAuthService oauthService;

  /** The available OAuth scopes for which {@link OAuthService} should check. */
  private final ImmutableSet<String> availableOauthScopes;

  /** The OAuth scopes which must all be present for authentication to succeed. */
  private final ImmutableSet<String> requiredOauthScopes;

  private final ImmutableSet<String> allowedOauthClientIds;

  @Inject
  public OAuthAuthenticationMechanism(
      OAuthService oauthService,
      @Config("availableOauthScopes") ImmutableSet<String> availableOauthScopes,
      @Config("requiredOauthScopes") ImmutableSet<String> requiredOauthScopes,
      @Config("allowedOauthClientIds") ImmutableSet<String> allowedOauthClientIds) {
    this.oauthService = oauthService;
    this.availableOauthScopes = availableOauthScopes;
    this.requiredOauthScopes = requiredOauthScopes;
    this.allowedOauthClientIds = allowedOauthClientIds;
  }

  @Override
  public AuthResult authenticate(HttpServletRequest request) {

    // Make sure that there is an Authorization header in Bearer form. OAuthService also accepts
    // tokens in the request body and URL string, but we should not use those, since they are more
    // likely to be logged than the Authorization header. Checking to make sure there's a token also
    // avoids unnecessary RPCs, since OAuthService itself does not check whether the header is
    // present. In theory, there could be more than one Authorization header, but we only check the
    // first one, because there's not a legitimate use case for having more than one, and
    // OAuthService itself only looks at the first one anyway.
    String header = request.getHeader(AUTHORIZATION);
    if ((header == null) || !header.startsWith(BEARER_PREFIX)) {
      if (header != null) {
        logger.atInfo().log("invalid authorization header");
      }
      return AuthResult.create(NONE);
    }
    // Assume that, if a bearer token is found, it's what OAuthService will use to attempt
    // authentication. This is not technically guaranteed by the contract of OAuthService; see
    // OAuthTokenInfo for more information.
    String rawAccessToken = header.substring(BEARER_PREFIX.length());

    // Get the OAuth information. The various oauthService method calls use a single cached
    // authentication result, so we can call them one by one.
    User currentUser;
    boolean isUserAdmin;
    String oauthClientId;
    ImmutableSet<String> authorizedScopes;
    try {
      String[] availableOauthScopeArray = availableOauthScopes.toArray(new String[0]);
      currentUser = oauthService.getCurrentUser(availableOauthScopeArray);
      isUserAdmin = oauthService.isUserAdmin(availableOauthScopeArray);
      logger.atInfo().log(
          "current user: %s (%s)", currentUser, isUserAdmin ? "admin" : "not admin");
      oauthClientId = oauthService.getClientId(availableOauthScopeArray);
      logger.atInfo().log("client ID: %s", oauthClientId);
      authorizedScopes =
          ImmutableSet.copyOf(oauthService.getAuthorizedScopes(availableOauthScopeArray));
      logger.atInfo().log("authorized scope(s): %s", authorizedScopes);
    } catch (OAuthRequestException | OAuthServiceFailureException e) {
      logger.atInfo().withCause(e).log("unable to get OAuth information");
      return AuthResult.create(NONE);
    }
    if ((currentUser == null) || (oauthClientId == null) || (authorizedScopes == null)) {
      return AuthResult.create(NONE);
    }

    // Make sure that the client ID matches, to avoid a confused deputy attack; see:
    // http://stackoverflow.com/a/17439317/1179226
    if (!allowedOauthClientIds.contains(oauthClientId)) {
      logger.atInfo().log("client ID is not allowed");
      return AuthResult.create(NONE);
    }

    // Make sure that all required scopes are present.
    if (!authorizedScopes.containsAll(requiredOauthScopes)) {
      logger.atInfo().log("required scope(s) missing");
      return AuthResult.create(NONE);
    }

    // Create the {@link AuthResult}, including the OAuth token info.
    return AuthResult.create(
        USER,
        UserAuthInfo.create(
            currentUser,
            isUserAdmin,
            OAuthTokenInfo.create(
                ImmutableSet.copyOf(authorizedScopes), oauthClientId, rawAccessToken)));
  }
}
