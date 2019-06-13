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

import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.base.Strings.nullToEmpty;
import static google.registry.request.auth.AuthLevel.NONE;
import static google.registry.request.auth.AuthLevel.USER;
import static google.registry.security.XsrfTokenManager.P_CSRF_TOKEN;
import static google.registry.security.XsrfTokenManager.X_CSRF_TOKEN;

import com.google.appengine.api.users.UserService;
import com.google.common.collect.ImmutableSet;
import google.registry.security.XsrfTokenManager;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;

/**
 * Authentication mechanism for legacy cookie-based App Engine authentication.
 *
 * <p>Just use the values returned by UserService.
 */
public class LegacyAuthenticationMechanism implements AuthenticationMechanism {

  private final UserService userService;
  private final XsrfTokenManager xsrfTokenManager;

  /** HTTP methods which are considered safe, and do not require XSRF protection. */
  private static final ImmutableSet<String> SAFE_METHODS = ImmutableSet.of("GET", "HEAD");

  @Inject
  public LegacyAuthenticationMechanism(UserService userService, XsrfTokenManager xsrfTokenManager) {
    this.userService = userService;
    this.xsrfTokenManager = xsrfTokenManager;
  }

  @Override
  public AuthResult authenticate(HttpServletRequest request) {
    if (!userService.isUserLoggedIn()) {
      return AuthResult.create(NONE);
    }

    if (!SAFE_METHODS.contains(request.getMethod()) && !validateXsrf(request)) {
      return AuthResult.create(NONE);
    }

    return AuthResult.create(
        USER,
        UserAuthInfo.create(userService.getCurrentUser(), userService.isUserAdmin()));
  }

  private boolean validateXsrf(HttpServletRequest request) {
    String headerToken = emptyToNull(request.getHeader(X_CSRF_TOKEN));
    if (headerToken != null) {
      return xsrfTokenManager.validateToken(headerToken);
    }
    // If we got here - the header didn't have the token.
    // It might be in the POST data - however even checking whether the POST data has this entry
    // could break the Action!
    //
    // Reason: if we do request.getParameter, any Action that injects @Payload or @JsonPayload
    // would break since it uses request.getReader - and it's an error to call both getReader and
    // getParameter!
    //
    // However, in this case it's acceptable since if we got here - the POST request didn't even
    // have the XSRF header meaning if it doesn't have POST data - it's not from a valid source at
    // all (a valid but outdated source would have a bad header value, but getting here means we had
    // no value at all)
    //
    // TODO(b/120201577): Once we know from the @Action whether we can use getParameter or not -
    // only check getParameter if that's how this @Action uses getParameters.
    return xsrfTokenManager.validateToken(nullToEmpty(request.getParameter(P_CSRF_TOKEN)));
  }
}
