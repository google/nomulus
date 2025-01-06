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

package google.registry.request.auth;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.request.auth.AuthSettings.AuthLevel.APP;
import static google.registry.request.auth.AuthSettings.AuthLevel.NONE;
import static google.registry.request.auth.AuthSettings.AuthLevel.USER;
import static google.registry.request.auth.AuthSettings.UserPolicy.ADMIN;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import javax.inject.Inject;

/** Top-level authentication/authorization class; calls authentication mechanisms as needed. */
public class RequestAuthenticator {

  private final ImmutableList<AuthenticationMechanism> apiAuthenticationMechanisms;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject
  public RequestAuthenticator(ImmutableList<AuthenticationMechanism> apiAuthenticationMechanisms) {
    this.apiAuthenticationMechanisms = apiAuthenticationMechanisms;
  }

  /**
   * Attempts to authenticate and authorize the user, according to the settings of the action.
   *
   * @param auth the auth settings of the action, which determine what authentication and
   *     authorization are allowed
   * @param req the {@link HttpServletRequest}; some authentication mechanisms use HTTP headers
   * @return an authentication result if authentication/authorization was successful, or absent() if
   *     not; authentication can be "successful" even without any authentication if the action's
   *     auth settings are set to NONE -- in this case, NOT_AUTHENTICATED is returned
   */
  public Optional<AuthResult> authorize(AuthSettings auth, HttpServletRequest req) {
    logger.atInfo().log("Action requires auth: %s", auth);
    AuthResult authResult = authenticate(auth, req);
    if (auth.minimumLevel() == APP && !authResult.isAuthenticated()) {
      logger.atWarning().log("Not authorized; no authentication found.");
      return Optional.empty();
    }
    if (auth.minimumLevel() == USER && authResult.authLevel() != USER) {
      logger.atWarning().log("Not authorized; no authenticated user.");
      return Optional.empty();
    }
    if (auth.userPolicy() == ADMIN
        && authResult.user().isPresent()
        && !authResult.user().get().getUserRoles().isAdmin()) {
      logger.atWarning().log(
          "Not authorized; user policy is ADMIN, but the user was not an admin.");
      return Optional.empty();
    }
    return Optional.of(authResult);
  }

  /**
   * Attempts to authenticate the user, according to the settings of the action.
   *
   * @param auth the auth settings of the action, which determine what authentication is allowed
   * @param req the {@link HttpServletRequest}; some authentication mechanisms use HTTP headers
   * @return an authentication result; if no authentication was made, returns NOT_AUTHENTICATED
   */
  AuthResult authenticate(AuthSettings auth, HttpServletRequest req) {
    checkAuthConfig(auth);
    AuthResult authResult;
    for (AuthenticationMechanism authMechanism : apiAuthenticationMechanisms) {
      authResult = authMechanism.authenticate(req);
      if (authResult.isAuthenticated()) {
        logger.atInfo().log(
            "Authenticated via %s: %s", authMechanism.getClass().getSimpleName(), authResult);
        return authResult;
      }
    }
    logger.atInfo().log("No authentication found.");
    return AuthResult.NOT_AUTHENTICATED;
  }

  /** Validates an AuthSettings object, checking for invalid setting combinations. */
  static void checkAuthConfig(AuthSettings auth) {
    checkArgument(
        (auth.minimumLevel() != NONE) || (auth.userPolicy() != ADMIN),
        "Actions with minimal auth level at NONE should not specify ADMIN user policy");
  }
}
