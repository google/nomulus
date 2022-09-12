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

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.common.flogger.FluentLogger;
import google.registry.model.console.User;
import google.registry.model.console.UserDao;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

/**
 * A way to authenticate HTTP requests using OAuth2 ID tokens stored in cookies.
 *
 * <p>This is generic to Google Single-Sign-On and doesn't have any ties with Google App Engine.
 */
public class CookieOAuth2AuthenticationMechanism implements AuthenticationMechanism {

  private static final String ID_TOKEN_COOKIE_NAME = "idToken";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final GoogleIdTokenVerifier googleIdTokenVerifier;

  @Inject
  public CookieOAuth2AuthenticationMechanism(GoogleIdTokenVerifier googleIdTokenVerifier) {
    this.googleIdTokenVerifier = googleIdTokenVerifier;
  }

  @Override
  public AuthResult authenticate(HttpServletRequest request) {
    String rawIdToken = getRawIdTokenFromCookie(request);
    if (rawIdToken == null) {
      return AuthResult.NOT_AUTHENTICATED;
    }
    GoogleIdToken googleIdToken;
    try {
      googleIdToken = googleIdTokenVerifier.verify(rawIdToken);
    } catch (IOException | GeneralSecurityException e) {
      logger.atInfo().withCause(e).log("Error when verifying access token");
      return AuthResult.NOT_AUTHENTICATED;
    }
    // A null token means the provided ID token was invalid or expired
    if (googleIdToken == null) {
      logger.atInfo().log("Token %s failed validation", rawIdToken);
      return AuthResult.NOT_AUTHENTICATED;
    }
    String emailAddress = googleIdToken.getPayload().getEmail();
    Optional<User> maybeUser = UserDao.loadUser(emailAddress);
    if (!maybeUser.isPresent()) {
      logger.atInfo().log("No user found for email address %s", emailAddress);
      return AuthResult.NOT_AUTHENTICATED;
    }
    return AuthResult.create(AuthLevel.USER, UserAuthInfo.create(maybeUser.get()));
  }

  @Nullable
  private String getRawIdTokenFromCookie(HttpServletRequest request) {
    if (request.getCookies() == null) {
      logger.atInfo().log("No cookies passed in request");
      return null;
    }
    for (Cookie cookie : request.getCookies()) {
      if (cookie.getName().equals(ID_TOKEN_COOKIE_NAME)) {
        return cookie.getValue();
      }
    }
    logger.atInfo().log("No ID token cookie");
    return null;
  }
}
