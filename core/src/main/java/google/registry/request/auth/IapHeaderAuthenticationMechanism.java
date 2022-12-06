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
import google.registry.model.console.UserDao;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;

/**
 * A way to authenticate HTTP requests that have gone through the GCP Identity-Aware Proxy.
 *
 * <p>When the user logs in, IAP provides a JWT in the <code>X-Goog-IAP-JWT-Assertion</code> header.
 * This header is included on all requests to IAP-enabled services (which should be all of them that
 * receive requests from the front end). The token verification libraries ensure that the signed
 * token has the proper audience and issuer.
 *
 * @see <a href="https://cloud.google.com/iap/docs/signed-headers-howto">the documentation on GCP
 *     IAP's signed headers for more information.</a>
 */
public class IapHeaderAuthenticationMechanism implements AuthenticationMechanism {

  private static final String ID_TOKEN_HEADER_NAME = "X-Goog-IAP-JWT-Assertion";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // A workaround that allows "use" of the IAP-based authenticator when running local testing, i.e.
  // the RegistryTestServer
  private static Optional<User> userForTesting = Optional.empty();

  private final TokenVerifier tokenVerifier;

  @Inject
  public IapHeaderAuthenticationMechanism(TokenVerifier tokenVerifier) {
    this.tokenVerifier = tokenVerifier;
  }

  @Override
  public AuthResult authenticate(HttpServletRequest request) {
    if (RegistryEnvironment.get().equals(RegistryEnvironment.UNITTEST)
        && userForTesting.isPresent()) {
      return AuthResult.create(AuthLevel.USER, UserAuthInfo.create(userForTesting.get()));
    }
    String rawIdToken = request.getHeader(ID_TOKEN_HEADER_NAME);
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
    Optional<User> maybeUser = UserDao.loadUser(emailAddress);
    if (!maybeUser.isPresent()) {
      logger.atInfo().log("No user found for email address %s", emailAddress);
      return AuthResult.NOT_AUTHENTICATED;
    }
    return AuthResult.create(AuthLevel.USER, UserAuthInfo.create(maybeUser.get()));
  }

  @VisibleForTesting
  public static void setUserAuthInfoForTestServer(@Nullable User user) {
    userForTesting = Optional.ofNullable(user);
  }
}
