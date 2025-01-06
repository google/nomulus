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
import static google.registry.request.auth.AuthSettings.AuthLevel.USER;

import google.registry.model.console.User;
import google.registry.request.auth.AuthSettings.AuthLevel;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Results of authentication for a given HTTP request, as emitted by an {@link
 * AuthenticationMechanism}.
 *
 * @param authLevel the level of authentication obtained
 * @param user information about the authenticated user, if there is one
 * @param serviceAccountEmail service account email of the authenticated app, if there is one
 */
public record AuthResult(
    AuthLevel authLevel, Optional<User> user, Optional<String> serviceAccountEmail) {

  public boolean isAuthenticated() {
    return authLevel() != AuthLevel.NONE;
  }

  public String userIdForLogging() {
    return user.map(
            user ->
                String.format(
                    "%s %s",
                    user.getUserRoles().isAdmin() ? "admin" : "user", user.getEmailAddress()))
        .orElse("<logged-out user>");
  }

  public static AuthResult createApp(String email) {
    return create(APP, null, email);
  }

  public static AuthResult createUser(User user) {
    return create(USER, user, null);
  }

  private static AuthResult create(
      AuthLevel authLevel, @Nullable User user, @Nullable String serviceAccountEmail) {
    checkArgument(
        user == null || serviceAccountEmail == null,
        "User and service account email cannot be specified at the same time");
    checkArgument(authLevel != USER || user != null, "User must be specified for auth level USER");
    checkArgument(
        authLevel != APP || serviceAccountEmail != null,
        "Service account email must be specified for auth level APP");
    return new AuthResult(
        authLevel, Optional.ofNullable(user), Optional.ofNullable(serviceAccountEmail));
  }

  /**
   * No authentication was made.
   *
   * <p>In the authentication step, this means that none of the configured authentication methods
   * were able to authenticate the user. But the authorization settings may be such that it's
   * perfectly fine not to be authenticated. The {@link RequestAuthenticator#authorize} method
   * returns NOT_AUTHENTICATED in this case, as opposed to absent() if authentication failed and was
   * required. So as a return from an authorization check, this can be treated as a success.
   */
  public static final AuthResult NOT_AUTHENTICATED = create(AuthLevel.NONE, null, null);
}
