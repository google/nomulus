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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Results of authentication for a given HTTP request, as emitted by an
 * {@link AuthenticationMechanism}.
 */
@AutoValue
public abstract class AuthResult {

  public abstract AuthLevel authLevel();

  /** Information about the authenticated user, if there is one. */
  public abstract Optional<UserAuthInfo> userAuthInfo();

  public boolean isAuthenticated() {
    return authLevel() != AuthLevel.NONE;
  }

  public String userIdForLogging() {
    return userAuthInfo()
        .map(
            userAuthInfo ->
                String.format(
                    "%s %s",
                    userAuthInfo.isUserAdmin() ? "admin" : "user", userAuthInfo.user().getEmail()))
        .orElse("<logged-out user>");
  }

  public static AuthResult create(AuthLevel authLevel) {
    return new AutoValue_AuthResult(authLevel, Optional.empty());
  }

  public static AuthResult create(AuthLevel authLevel, @Nullable UserAuthInfo userAuthInfo) {
    if (authLevel == AuthLevel.USER) {
      checkNotNull(userAuthInfo);
    }
    return new AutoValue_AuthResult(authLevel, Optional.ofNullable(userAuthInfo));
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
  public static final AuthResult NOT_AUTHENTICATED =
      AuthResult.create(AuthLevel.NONE);
}
