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

package google.registry.request;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_METHOD_NOT_ALLOWED;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

import com.google.common.flogger.FluentLogger;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.RequestAuthenticator;
import google.registry.util.NonFinalForTesting;
import google.registry.util.SystemClock;
import google.registry.util.TypeUtils.TypeInstantiator;
import java.io.IOException;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.inject.Provider;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Dagger-based request processor.
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
 * <h3>Security Features</h3>
 *
 * @param <C> request component type
 */
public class RequestHandler<C> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Router router;
  private final Provider<? extends RequestComponentBuilder<C>> requestComponentBuilderProvider;
  private final RequestAuthenticator requestAuthenticator;
  private final SystemClock clock = new SystemClock();

  @NonFinalForTesting
  RequestMetrics requestMetrics = new RequestMetrics();

  /**
   * Constructor for subclasses to create a new request handler for a specific request component.
   *
   * <p>This operation will generate a routing map for the component's {@code @Action}-returning
   * methods using reflection, which is moderately expensive, so a given servlet should construct a
   * single {@code RequestHandler} and re-use it across requests.
   *
   * @param requestComponentBuilderProvider a Dagger {@code Provider} of builder instances that can
   *     be used to construct new instances of the request component (with the required
   *     request-derived modules provided by this class)
   * @param requestAuthenticator an instance of the {@link RequestAuthenticator} class
   */
  protected RequestHandler(
      Provider<? extends RequestComponentBuilder<C>> requestComponentBuilderProvider,
      RequestAuthenticator requestAuthenticator) {
    this(null, requestComponentBuilderProvider, requestAuthenticator);
  }

  /** Creates a new RequestHandler with an explicit component class for test purposes. */
  public static <C> RequestHandler<C> createForTest(
      Class<C> component,
      Provider<? extends RequestComponentBuilder<C>> requestComponentBuilderProvider,
      RequestAuthenticator requestAuthenticator) {
    return new RequestHandler<>(
        checkNotNull(component),
        requestComponentBuilderProvider,
        requestAuthenticator);
  }

  private RequestHandler(
      @Nullable Class<C> component,
      Provider<? extends RequestComponentBuilder<C>> requestComponentBuilderProvider,
      RequestAuthenticator requestAuthenticator) {
    // If the component class isn't explicitly provided, infer it from the class's own typing.
    // This is safe only for use by subclasses of RequestHandler where the generic parameter is
    // preserved at runtime, so only expose that option via the protected constructor.
    this.router = Router.create(
        component != null ? component : new TypeInstantiator<C>(getClass()){}.getExactType());
    this.requestComponentBuilderProvider = checkNotNull(requestComponentBuilderProvider);
    this.requestAuthenticator = checkNotNull(requestAuthenticator);
  }

  /** Runs the appropriate action for a servlet request. */
  public void handleRequest(HttpServletRequest req, HttpServletResponse rsp) throws IOException {
    checkNotNull(req);
    checkNotNull(rsp);
    Action.Method method;
    try {
      method = Action.Method.valueOf(req.getMethod());
    } catch (IllegalArgumentException e) {
      logger.atInfo().log("Unsupported method: %s", req.getMethod());
      rsp.sendError(SC_METHOD_NOT_ALLOWED);
      return;
    }
    String path = req.getRequestURI();
    Optional<Route> route = router.route(path);
    if (!route.isPresent()) {
      logger.atInfo().log("No action found for: %s", path);
      rsp.sendError(SC_NOT_FOUND);
      return;
    }
    if (!route.get().isMethodAllowed(method)) {
      logger.atWarning().log("Method %s not allowed for: %s", method, path);
      rsp.sendError(SC_METHOD_NOT_ALLOWED);
      return;
    }
    Optional<AuthResult> authResult =
        requestAuthenticator.authorize(route.get().action().auth().authSettings(), req);
    if (!authResult.isPresent()) {
      rsp.sendError(SC_FORBIDDEN, "Not authorized");
      return;
    }

    // Build a new request component using any modules we've constructed by this point.
    C component = requestComponentBuilderProvider.get()
        .requestModule(new RequestModule(req, rsp, authResult.get()))
        .build();
    // Apply the selected Route to the component to produce an Action instance, and run it.
    boolean success = true;
    DateTime startTime = clock.nowUtc();
    try {
      route.get().instantiator().apply(component).run();
      if (route.get().action().automaticallyPrintOk()) {
        rsp.setContentType(PLAIN_TEXT_UTF_8.toString());
        rsp.getWriter().write("OK\n");
      }
    } catch (HttpException e) {
      e.send(rsp);
      success = false;
    } finally {
      requestMetrics.record(
          new Duration(startTime, clock.nowUtc()),
          path,
          method,
          authResult.get().authLevel(),
          success);
    }
  }
}
