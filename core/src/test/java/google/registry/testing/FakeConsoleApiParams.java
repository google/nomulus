// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.testing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.appengine.api.users.UserService;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.UserAuthInfo;
import google.registry.security.XsrfTokenManager;
import google.registry.ui.server.registrar.ConsoleApiParams;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.joda.time.DateTime;

public final class FakeConsoleApiParams {

  public static ConsoleApiParams get(Optional<AuthResult> maybeAuthResult) {
    AuthResult authResult =
        maybeAuthResult.orElseGet(
            () ->
                AuthResult.createUser(
                    UserAuthInfo.create(
                        new com.google.appengine.api.users.User(
                            "JohnDoe@theregistrar.com", "theregistrar.com"),
                        false)));
    HttpServletRequest request = mock(HttpServletRequest.class);
    XsrfTokenManager xsrfTokenManager =
        new XsrfTokenManager(
            new FakeClock(DateTime.parse("2020-02-02T01:23:45Z")), mock(UserService.class));
    when(request.getCookies())
        .thenReturn(
            new Cookie[] {
              new Cookie(XsrfTokenManager.X_CSRF_TOKEN, xsrfTokenManager.generateToken(""))
            });
    return ConsoleApiParams.create(request, new FakeResponse(), authResult, xsrfTokenManager);
  }
}
