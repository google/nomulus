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

package google.registry.ui.server.console;

import static google.registry.request.Action.Method.GET;

import com.google.api.client.http.HttpStatusCodes;
import google.registry.model.console.User;
import google.registry.security.XsrfTokenManager;
import google.registry.ui.server.registrar.ConsoleApiParams;
import java.util.Arrays;
import java.util.Optional;
import javax.servlet.http.Cookie;

/** Base class for handling Console API requests */
public abstract class ConsoleApiAction implements Runnable {
  protected ConsoleApiParams consoleApiParams;

  public ConsoleApiAction(ConsoleApiParams consoleApiParams) {
    this.consoleApiParams = consoleApiParams;
  }

  @Override
  public final void run() {
    // Shouldn't be even possible because of Auth annotations on the various implementing classes
    if (!consoleApiParams.authResult().userAuthInfo().get().consoleUser().isPresent()) {
      consoleApiParams.response().setStatus(HttpStatusCodes.STATUS_CODE_UNAUTHORIZED);
      return;
    }
    User user = consoleApiParams.authResult().userAuthInfo().get().consoleUser().get();
    if (consoleApiParams.request().getMethod().equals(GET.toString())) {
      getHandler(user);
    } else {
      if (verifyXSRF()) {
        postHandler(user);
      }
    }
  }

  protected void postHandler(User user) {
    throw new UnsupportedOperationException("Console API POST handler not implemented");
  }

  protected void getHandler(User user) {
    throw new UnsupportedOperationException("Console API GET handler not implemented");
  }

  private boolean verifyXSRF() {
    Optional<Cookie> maybeCookie =
        Arrays.stream(consoleApiParams.request().getCookies())
            .filter(c -> XsrfTokenManager.X_CSRF_TOKEN.equals(c.getName()))
            .findFirst();
    if (!maybeCookie.isPresent()
        || !consoleApiParams.xsrfTokenManager().validateToken(maybeCookie.get().getValue())) {
      consoleApiParams.response().setStatus(HttpStatusCodes.STATUS_CODE_UNAUTHORIZED);
      return false;
    }
    return true;
  }
}
