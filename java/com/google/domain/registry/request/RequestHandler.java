// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package com.google.domain.registry.request;

import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.net.HttpHeaders.LOCATION;
import static com.google.domain.registry.security.XsrfTokenManager.X_CSRF_TOKEN;
import static com.google.domain.registry.security.XsrfTokenManager.validateToken;
import static com.google.domain.registry.util.HttpServletUtils.sendOk;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_METHOD_NOT_ALLOWED;
import static javax.servlet.http.HttpServletResponse.SC_MOVED_TEMPORARILY;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;
import com.google.common.base.Optional;
import com.google.domain.registry.util.FormattingLogger;
import com.google.domain.registry.util.NonFinalForTesting;

import org.joda.time.Duration;

import java.io.IOException;
import java.lang.reflect.Method;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Dagger request processor for Domain Registry.
 *
 * <p>This class creates an HTTP request processor from a Dagger component. It routes requests from
 * your servlet to an {@link Action @Action} annotated handler class.
 *
 * <h3>Component Definition</h3>
 *
 * <p>Action instances are supplied on a per-request basis by invoking the methods on {@code C}.
 * For example:
 * <pre>
 * {@literal @Component}
 * interface ServerComponent {
 *   HelloAction helloAction();
 * }</pre>
 *
 * <p>The rules for component methods are as follows:
 * <ol>
 * <li>Methods whose raw return type does not implement {@code Runnable} will be ignored
 * <li>Methods whose raw return type does not have an {@code @Action} annotation are ignored
 * </ol>
 *
 * <p><b>Warning:</b> When using the App Engine platform, you must call
 * {@link Method#setAccessible(boolean) setAccessible(true)} on all your component {@link Method}
 * instances, from within the same package as the component. This is due to cross-package
 * reflection restrictions.
 *
 * <h3>Security Features</h3>
 *
 * <p>XSRF protection is built into this class. It can be enabled or disabled on individual actions
 * using {@link Action#xsrfProtection() xsrfProtection} setting.
 *
 * <p>This class also enforces the {@link Action#requireLogin() requireLogin} setting.
 *
 * @param <C> component type
 */
public final class RequestHandler<C extends RequestComponent<?>> {

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

  private static final Duration XSRF_VALIDITY = Duration.standardDays(1);

  @NonFinalForTesting
  private static UserService userService = UserServiceFactory.getUserService();

  private final C component;
  private final HttpServletResponse rsp;
  private final HttpServletRequest req;
  private final Optional<Route> route;

  @Inject
  public RequestHandler(
      C component, HttpServletResponse rsp, HttpServletRequest req, Optional<Route> route) {
    this.component = component;
    this.rsp = rsp;
    this.req = req;
    this.route = route;
  }

  /**
   * Runs the appropriate action for a servlet request.
   */
  public void handleRequest() throws IOException {
    Action.Method method;
    try {
      method = Action.Method.valueOf(req.getMethod());
    } catch (IllegalArgumentException e) {
      logger.infofmt("Unsupported method: %s", req.getMethod());
      rsp.sendError(SC_METHOD_NOT_ALLOWED);
      return;
    }
    String path = req.getRequestURI();
    if (!route.isPresent()) {
      logger.infofmt("No action found for: %s", path);
      rsp.sendError(SC_NOT_FOUND);
      return;
    }
    if (!route.get().isMethodAllowed(method)) {
      logger.infofmt("Method %s not allowed for: %s", method, path);
      rsp.sendError(SC_METHOD_NOT_ALLOWED);
      return;
    }
    if (route.get().action().requireLogin() && !userService.isUserLoggedIn()) {
      logger.info("not logged in");
      rsp.setStatus(SC_MOVED_TEMPORARILY);
      rsp.setHeader(LOCATION, userService.createLoginURL(req.getRequestURI()));
      return;
    }
    if (route.get().shouldXsrfProtect(method)
        && !validateToken(
                nullToEmpty(req.getHeader(X_CSRF_TOKEN)),
                route.get().action().xsrfScope(),
                XSRF_VALIDITY)) {
      rsp.sendError(SC_FORBIDDEN, "Invalid " + X_CSRF_TOKEN);
      return;
    }
    try {
      route.get().instantiator().apply(component).run();
      if (route.get().action().automaticallyPrintOk()) {
        sendOk(rsp);
      }
    } catch (HttpException e) {
      e.send(rsp);
    }
  }
}
