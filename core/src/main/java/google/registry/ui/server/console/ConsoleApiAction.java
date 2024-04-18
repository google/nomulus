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
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;

import com.google.common.base.Throwables;
import com.google.common.flogger.FluentLogger;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.request.HttpException;
import google.registry.security.XsrfTokenManager;
import google.registry.ui.server.registrar.ConsoleApiParams;
import google.registry.ui.server.registrar.ConsoleUiAction;
import google.registry.util.RegistryEnvironment;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

/** Base class for handling Console API requests */
public abstract class ConsoleApiAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  protected ConsoleApiParams consoleApiParams;

  public ConsoleApiAction(ConsoleApiParams consoleApiParams) {
    this.consoleApiParams = consoleApiParams;
  }

  @Override
  public final void run() {
    // Shouldn't be even possible because of Auth annotations on the various implementing classes
    if (consoleApiParams.authResult().user().isEmpty()) {
      consoleApiParams.response().setStatus(SC_UNAUTHORIZED);
      return;
    }
    User user = consoleApiParams.authResult().user().get();

    // This allows us to enable console to a selected cohort of users with release
    // We can ignore it in tests
    if (RegistryEnvironment.get() != RegistryEnvironment.UNITTEST
        && GlobalRole.NONE.equals(user.getUserRoles().getGlobalRole())) {
      try {
        consoleApiParams.response().sendRedirect(ConsoleUiAction.PATH);
        return;
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    try {
      if (consoleApiParams.request().getMethod().equals(GET.toString())) {
        getHandler(user);
      } else {
        if (verifyXSRF(user)) {
          postHandler(user);
        }
      }
    } catch (ConsolePermissionForbiddenException e) {
      logger.atWarning().withCause(e).log("Forbidden");
      setFailedResponse("", SC_FORBIDDEN);
    } catch (HttpException.BadRequestException | IllegalArgumentException e) {
      logger.atWarning().withCause(e).log("Error in request");
      setFailedResponse(Throwables.getRootCause(e).getMessage(), SC_BAD_REQUEST);
    } catch (Throwable t) {
      logger.atWarning().withCause(t).log("Internal server error");
      setFailedResponse(Throwables.getRootCause(t).getMessage(), SC_INTERNAL_SERVER_ERROR);
    }
  }

  protected void checkPermission(User user, String registrarId, ConsolePermission permission) {
    if (!user.getUserRoles().hasPermission(registrarId, permission)) {
      throw new ConsolePermissionForbiddenException(
          String.format(
              "User %s does not have permission %s on registrar %s",
              user.getEmailAddress(), permission, registrarId));
    }
  }

  protected void postHandler(User user) {
    throw new UnsupportedOperationException("Console API POST handler not implemented");
  }

  protected void getHandler(User user) {
    throw new UnsupportedOperationException("Console API GET handler not implemented");
  }

  protected void setFailedResponse(String message, int code) {
    consoleApiParams.response().setStatus(code);
    consoleApiParams.response().setPayload(message);
  }

  private boolean verifyXSRF(User user) {
    Optional<Cookie> maybeCookie =
        Arrays.stream(consoleApiParams.request().getCookies())
            .filter(c -> XsrfTokenManager.X_CSRF_TOKEN.equals(c.getName()))
            .findFirst();
    if (maybeCookie.isEmpty()
        || !consoleApiParams
            .xsrfTokenManager()
            .validateToken(user.getEmailAddress(), maybeCookie.get().getValue())) {
      consoleApiParams.response().setStatus(SC_UNAUTHORIZED);
      return false;
    }
    return true;
  }

  /** Specialized exception class used for failure when a user doesn't have the right permission. */
  private static class ConsolePermissionForbiddenException extends RuntimeException {
    private ConsolePermissionForbiddenException(String message) {
      super(message);
    }
  }
}
