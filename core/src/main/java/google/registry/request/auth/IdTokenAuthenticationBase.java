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

import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.auth.oauth2.TokenVerifier;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryEnvironment;
import google.registry.model.console.User;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;

public abstract class IdTokenAuthenticationBase implements AuthenticationMechanism {

  public static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // A workaround that allows "use" of the IAP-based authenticator when running local testing, i.e.
  // the RegistryTestServer
  private static Optional<User> userForTesting = Optional.empty();

  private final TokenVerifier tokenVerifier;

  public IdTokenAuthenticationBase(TokenVerifier tokenVerifier) {
    this.tokenVerifier = tokenVerifier;
  }

  abstract String rawTokenFromRequest(HttpServletRequest request);
  abstract AuthResult authResultFromEmail(String email);

  @Override
  public AuthResult authenticate(HttpServletRequest request) {
    if (RegistryEnvironment.get().equals(RegistryEnvironment.UNITTEST)
        && userForTesting.isPresent()) {
      return AuthResult.create(AuthLevel.USER, UserAuthInfo.create(userForTesting.get()));
    }
    String rawIdToken = rawTokenFromRequest(request);
    if (rawIdToken == null) {
      return AuthResult.NOT_AUTHENTICATED;
    }
    JsonWebSignature token;
    try {
      token = tokenVerifier.verify(rawIdToken);
    } catch (TokenVerifier.VerificationException e) {
      logger.atInfo().withCause(e).log("Error when verifying access token");
      return AuthResult.NOT_AUTHENTICATED;
    }
    String emailAddress = (String) token.getPayload().get("email");
    return authResultFromEmail(emailAddress);
  }

  @VisibleForTesting
  public static void setUserAuthInfoForTestServer(@Nullable User user) {
    userForTesting = Optional.ofNullable(user);
  }
}
