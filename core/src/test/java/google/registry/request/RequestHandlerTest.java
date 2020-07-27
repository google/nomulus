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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static google.registry.request.auth.Auth.AUTH_INTERNAL_OR_ADMIN;
import static google.registry.request.auth.Auth.AUTH_PUBLIC;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.appengine.api.users.User;
import com.google.common.testing.NullPointerTester;
import google.registry.request.HttpException.ServiceUnavailableException;
import google.registry.request.auth.AuthLevel;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.RequestAuthenticator;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.AppEngineRule;
import google.registry.testing.UserInfo;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RequestHandler}. */
public final class RequestHandlerTest {

  @RegisterExtension
  final AppEngineRule appEngine =
      AppEngineRule.builder()
          .withDatastoreAndCloudSql()
          .withUserService(UserInfo.create("test@example.com", "test@example.com"))
          .build();

  @Action(
      service = Action.Service.DEFAULT,
      path = "/bumblebee",
      method = {GET, POST},
      isPrefix = true,
      auth = AUTH_PUBLIC)
  public static class BumblebeeTask implements Runnable {
    @Override
    public void run() {}
  }

  @Action(
      service = Action.Service.DEFAULT,
      path = "/sloth",
      method = POST,
      automaticallyPrintOk = true,
      auth = AUTH_PUBLIC)
  public static class SlothTask implements Runnable {
    @Override
    public void run() {}
  }

  @Action(
      service = Action.Service.DEFAULT,
      path = "/safe-sloth",
      method = {GET, POST},
      auth = AUTH_PUBLIC)
  public static class SafeSlothTask implements Runnable {
    @Override
    public void run() {}
  }

  @Action(service = Action.Service.DEFAULT, path = "/fail", auth = AUTH_PUBLIC)
  public static final class FailTask implements Runnable {
    @Override
    public void run() {
      throw new ServiceUnavailableException("Set sail for fail");
    }
  }

  @Action(service = Action.Service.DEFAULT, path = "/failAtConstruction", auth = AUTH_PUBLIC)
  public static final class FailAtConstructionTask implements Runnable {
    FailAtConstructionTask() {
      throw new ServiceUnavailableException("Fail at construction");
    }

    @Override
    public void run() {
      throw new AssertionError("should not get here");
    }
  }

  class AuthBase implements Runnable {
    private final AuthResult authResult;

    AuthBase(AuthResult authResult) {
      this.authResult = authResult;
    }

    @Override
    public void run() {
      providedAuthResult = authResult;
    }
  }

  @Action(service = Action.Service.DEFAULT, path = "/auth/none", auth = AUTH_PUBLIC, method = GET)
  public class AuthNoneAction extends AuthBase {
    AuthNoneAction(AuthResult authResult) {
      super(authResult);
    }
  }

  @Action(
      service = Action.Service.DEFAULT,
      path = "/auth/adminUser",
      auth = AUTH_INTERNAL_OR_ADMIN,
      method = GET)
  public class AuthAdminUserAction extends AuthBase {
    AuthAdminUserAction(AuthResult authResult) {
      super(authResult);
    }
  }

  public class Component {

    private RequestModule requestModule = null;

    public RequestModule getRequestModule() {
      return requestModule;
    }

    public void setRequestModule(RequestModule requestModule) {
      this.requestModule = requestModule;
    }

    public BumblebeeTask bumblebeeTask() {
      return bumblebeeTask;
    }

    public SlothTask slothTask() {
      return slothTask;
    }

    public SafeSlothTask safeSlothTask() {
      return safeSlothTask;
    }

    public FailTask failTask() {
      return new FailTask();
    }

    public FailAtConstructionTask failAtConstructionTask() {
      return new FailAtConstructionTask();
    }

    public AuthNoneAction authNoneAction() {
      return new AuthNoneAction(component.getRequestModule().provideAuthResult());
    }

    public AuthAdminUserAction authAdminUserAction() {
      return new AuthAdminUserAction(component.getRequestModule().provideAuthResult());
    }
  }

  /** Fake Builder for the fake component above to satisfy RequestHandler expectations. */
  public abstract class Builder implements RequestComponentBuilder<Component> {
    @Override
    public Builder requestModule(RequestModule requestModule) {
      component.setRequestModule(requestModule);
      return this;
    }
  }

  private final HttpServletRequest req = mock(HttpServletRequest.class);
  private final HttpServletResponse rsp = mock(HttpServletResponse.class);
  private final BumblebeeTask bumblebeeTask = mock(BumblebeeTask.class);
  private final SlothTask slothTask = mock(SlothTask.class);
  private final SafeSlothTask safeSlothTask = mock(SafeSlothTask.class);
  private final RequestAuthenticator requestAuthenticator = mock(RequestAuthenticator.class);
  private final RequestMetrics requestMetrics = mock(RequestMetrics.class);

  private final Component component = new Component();
  private final StringWriter httpOutput = new StringWriter();
  private RequestHandler<Component> handler;
  private AuthResult providedAuthResult = null;
  private final User testUser = new User("test@example.com", "test@example.com");

  @BeforeEach
  void beforeEach() throws Exception {
    // Initialize here, not inline, so that we pick up the mocked UserService.
    handler =
        RequestHandler.createForTest(
            Component.class,
            () ->
                new Builder() {
                  @Override
                  public Component build() {
                    // Use a fake Builder that returns the single component instance using the mocks
                    return component;
                  }
                },
            requestAuthenticator);
    when(rsp.getWriter()).thenReturn(new PrintWriter(httpOutput));
    handler.requestMetrics = requestMetrics;
  }

  @AfterEach
  void afterEach() {
    verifyNoMoreInteractions(rsp, bumblebeeTask, slothTask, safeSlothTask, requestMetrics);
  }

  private void assertMetric(
      String path, Action.Method method, AuthLevel authLevel, boolean success) {
    verify(requestMetrics).record(any(), eq(path), eq(method), eq(authLevel), eq(success));
  }

  @Test
  void testHandleRequest_normalRequest_works() throws Exception {
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/bumblebee");
    when(requestAuthenticator.authorize(AUTH_PUBLIC.authSettings(), req))
        .thenReturn(Optional.of(AuthResult.create(AuthLevel.NONE)));

    handler.handleRequest(req, rsp);

    verifyNoInteractions(rsp);
    verify(bumblebeeTask).run();
    assertMetric("/bumblebee", GET, AuthLevel.NONE, true);
  }

  @Test
  void testHandleRequest_multipleMethodMappings_works() throws Exception {
    when(req.getMethod()).thenReturn("POST");
    when(req.getRequestURI()).thenReturn("/bumblebee");
    when(requestAuthenticator.authorize(AUTH_PUBLIC.authSettings(), req))
        .thenReturn(Optional.of(AuthResult.create(AuthLevel.NONE)));

    handler.handleRequest(req, rsp);

    verify(bumblebeeTask).run();
    assertMetric("/bumblebee", POST, AuthLevel.NONE, true);
  }

  @Test
  void testHandleRequest_prefixEnabled_subpathsWork() throws Exception {
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/bumblebee/hive");
    when(requestAuthenticator.authorize(AUTH_PUBLIC.authSettings(), req))
        .thenReturn(Optional.of(AuthResult.create(AuthLevel.NONE)));

    handler.handleRequest(req, rsp);

    verify(bumblebeeTask).run();
    assertMetric("/bumblebee/hive", GET, AuthLevel.NONE, true);
  }

  @Test
  void testHandleRequest_taskHasAutoPrintOk_printsOk() throws Exception {
    when(req.getMethod()).thenReturn("POST");
    when(req.getRequestURI()).thenReturn("/sloth");
    when(requestAuthenticator.authorize(AUTH_PUBLIC.authSettings(), req))
        .thenReturn(Optional.of(AuthResult.create(AuthLevel.NONE)));

    handler.handleRequest(req, rsp);

    verify(slothTask).run();
    verify(rsp).setContentType("text/plain; charset=utf-8");
    verify(rsp).getWriter();
    assertThat(httpOutput.toString()).isEqualTo("OK\n");
    assertMetric("/sloth", POST, AuthLevel.NONE, true);
  }

  @Test
  void testHandleRequest_prefixDisabled_subpathsReturn404NotFound() throws Exception {
    when(req.getMethod()).thenReturn("POST");
    when(req.getRequestURI()).thenReturn("/sloth/nest");
    when(requestAuthenticator.authorize(AUTH_PUBLIC.authSettings(), req))
        .thenReturn(Optional.of(AuthResult.create(AuthLevel.NONE)));

    handler.handleRequest(req, rsp);

    verify(rsp).sendError(404);
  }

  @Test
  void testHandleRequest_taskThrowsHttpException_getsHandledByHandler() throws Exception {
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/fail");
    when(requestAuthenticator.authorize(AUTH_PUBLIC.authSettings(), req))
        .thenReturn(Optional.of(AuthResult.create(AuthLevel.NONE)));

    handler.handleRequest(req, rsp);

    verify(rsp).sendError(503, "Set sail for fail");
    assertMetric("/fail", GET, AuthLevel.NONE, false);
  }

  /** Test for a regression of the issue in b/21377705. */
  @Test
  void testHandleRequest_taskThrowsHttpException_atConstructionTime_getsHandledByHandler()
      throws Exception {
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/failAtConstruction");
    when(requestAuthenticator.authorize(AUTH_PUBLIC.authSettings(), req))
        .thenReturn(Optional.of(AuthResult.create(AuthLevel.NONE)));

    handler.handleRequest(req, rsp);

    verify(rsp).sendError(503, "Fail at construction");
    assertMetric("/failAtConstruction", GET, AuthLevel.NONE, false);
  }

  @Test
  void testHandleRequest_notFound_returns404NotFound() throws Exception {
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/bogus");
    when(requestAuthenticator.authorize(AUTH_PUBLIC.authSettings(), req))
        .thenReturn(Optional.of(AuthResult.create(AuthLevel.NONE)));

    handler.handleRequest(req, rsp);

    verify(rsp).sendError(404);
  }

  @Test
  void testHandleRequest_methodNotAllowed_returns405MethodNotAllowed() throws Exception {
    when(req.getMethod()).thenReturn("POST");
    when(req.getRequestURI()).thenReturn("/fail");
    when(requestAuthenticator.authorize(AUTH_PUBLIC.authSettings(), req))
        .thenReturn(Optional.of(AuthResult.create(AuthLevel.NONE)));

    handler.handleRequest(req, rsp);

    verify(rsp).sendError(405);
  }

  @Test
  void testHandleRequest_insaneMethod_returns405MethodNotAllowed() throws Exception {
    when(req.getMethod()).thenReturn("FIREAWAY");
    when(req.getRequestURI()).thenReturn("/fail");
    when(requestAuthenticator.authorize(AUTH_PUBLIC.authSettings(), req))
        .thenReturn(Optional.of(AuthResult.create(AuthLevel.NONE)));

    handler.handleRequest(req, rsp);

    verify(rsp).sendError(405);
  }

  /**
   * @see <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec5.html#sec5.1.1">RFC2616 -
   *     HTTP/1.1 - Method</a>
   */
  @Test
  void testHandleRequest_lowercaseMethod_notRecognized() throws Exception {
    when(req.getMethod()).thenReturn("get");
    when(req.getRequestURI()).thenReturn("/bumblebee");
    when(requestAuthenticator.authorize(AUTH_PUBLIC.authSettings(), req))
        .thenReturn(Optional.of(AuthResult.create(AuthLevel.NONE)));

    handler.handleRequest(req, rsp);

    verify(rsp).sendError(405);
  }

  @SuppressWarnings("UnstableApiUsage")
  @Test
  void testNullness() {
    NullPointerTester tester = new NullPointerTester();
    tester.setDefault(Class.class, Component.class);
    tester.setDefault(RequestAuthenticator.class, requestAuthenticator);
    tester.testAllPublicStaticMethods(RequestHandler.class);
    tester.testAllPublicInstanceMethods(handler);
  }

  @Test
  void testXsrfProtection_validTokenProvided_runsAction() throws Exception {
    when(req.getMethod()).thenReturn("POST");
    when(req.getRequestURI()).thenReturn("/safe-sloth");
    when(requestAuthenticator.authorize(AUTH_PUBLIC.authSettings(), req))
        .thenReturn(Optional.of(AuthResult.create(AuthLevel.NONE)));

    handler.handleRequest(req, rsp);

    verify(safeSlothTask).run();
    assertMetric("/safe-sloth", POST, AuthLevel.NONE, true);
  }

  @Test
  void testXsrfProtection_GETMethodWithoutToken_doesntCheckToken() throws Exception {
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/safe-sloth");
    when(requestAuthenticator.authorize(AUTH_PUBLIC.authSettings(), req))
        .thenReturn(Optional.of(AuthResult.create(AuthLevel.NONE)));

    handler.handleRequest(req, rsp);

    verify(safeSlothTask).run();
    assertMetric("/safe-sloth", GET, AuthLevel.NONE, true);
  }

  @Test
  void testNoAuthNeeded_success() throws Exception {
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/auth/none");
    when(requestAuthenticator.authorize(AUTH_PUBLIC.authSettings(), req))
        .thenReturn(Optional.of(AuthResult.create(AuthLevel.NONE)));

    handler.handleRequest(req, rsp);

    assertThat(providedAuthResult).isNotNull();
    assertThat(providedAuthResult.authLevel()).isEqualTo(AuthLevel.NONE);
    assertThat(providedAuthResult.userAuthInfo()).isEmpty();
    assertMetric("/auth/none", GET, AuthLevel.NONE, true);
  }

  @Test
  void testAuthNeeded_failure() throws Exception {
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/auth/adminUser");
    when(requestAuthenticator.authorize(AUTH_INTERNAL_OR_ADMIN.authSettings(), req))
        .thenReturn(Optional.empty());

    handler.handleRequest(req, rsp);

    verify(rsp).sendError(403, "Not authorized");
    assertThat(providedAuthResult).isNull();
  }

  @Test
  void testAuthNeeded_success() throws Exception {
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/auth/adminUser");
    when(requestAuthenticator.authorize(AUTH_INTERNAL_OR_ADMIN.authSettings(), req))
        .thenReturn(
            Optional.of(AuthResult.create(AuthLevel.USER, UserAuthInfo.create(testUser, true))));

    handler.handleRequest(req, rsp);

    assertThat(providedAuthResult).isNotNull();
    assertThat(providedAuthResult.authLevel()).isEqualTo(AuthLevel.USER);
    assertThat(providedAuthResult.userAuthInfo()).isPresent();
    assertThat(providedAuthResult.userAuthInfo().get().user()).isEqualTo(testUser);
    assertThat(providedAuthResult.userAuthInfo().get().oauthTokenInfo()).isEmpty();
    assertMetric("/auth/adminUser", GET, AuthLevel.USER, true);
  }
}
