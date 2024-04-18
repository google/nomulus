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

import google.registry.model.console.User;
import google.registry.request.auth.AuthResult;
import google.registry.security.XsrfTokenManager;
import google.registry.ui.server.registrar.ConsoleApiParams;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.joda.time.DateTime;

public final class ConsoleApiParamsUtils {

  public static ConsoleApiParams createFake(AuthResult authResult) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    XsrfTokenManager xsrfTokenManager =
        new XsrfTokenManager(new FakeClock(DateTime.parse("2020-02-02T01:23:45Z")));
    when(request.getCookies())
        .thenReturn(
            new Cookie[] {
              new Cookie(
                  XsrfTokenManager.X_CSRF_TOKEN,
                  xsrfTokenManager.generateToken(
                      authResult.user().map(User::getEmailAddress).orElse("")))
            });
    return ConsoleApiParams.create(request, new FakeResponse(), authResult, xsrfTokenManager);
  }
}
