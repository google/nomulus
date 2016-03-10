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

import static com.google.common.truth.Truth.assertThat;
import static com.google.domain.registry.request.Action.Method.GET;
import static com.google.domain.registry.request.Action.Method.POST;
import static com.google.domain.registry.security.XsrfTokenManager.generateToken;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.google.appengine.api.users.UserService;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.testing.NullPointerTester;
import com.google.domain.registry.request.Action.Method;
import com.google.domain.registry.request.HttpException.ServiceUnavailableException;
import com.google.domain.registry.testing.AppEngineRule;
import com.google.domain.registry.testing.InjectRule;
import com.google.domain.registry.testing.UserInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Unit tests for {@link RequestHandler}. */
@RunWith(MockitoJUnitRunner.class)
public final class RequestHandlerTest {

  @Rule
  public final AppEngineRule appEngine =
      AppEngineRule.builder()
          .withDatastore()
          .withUserService(UserInfo.create("test@example.com", "test@example.com"))
          .build();

  @Rule
  public final InjectRule inject = new InjectRule();

  @Action(path = "/bumblebee", method = {GET, POST}, isPrefix = true)
  public class BumblebeeTask implements Runnable {
    @Override
    public void run() {}
  }

  @Action(path = "/sloth", method = POST, automaticallyPrintOk = true)
  public class SlothTask implements Runnable {
    @Override
    public void run() {}
  }

  @Action(path = "/safe-sloth", method = {GET, POST}, xsrfProtection = true, xsrfScope = "vampire")
  public class SafeSlothTask implements Runnable {
    @Override
    public void run() {}
  }

  @Action(path = "/users-only", method = GET, requireLogin = true)
  public class UsersOnlyAction implements Runnable {
    @Override
    public void run() {}
  }

  @Action(path = "/fail")
  public final class FailTask implements Runnable {
    @Override
    public void run() {
      throw new ServiceUnavailableException("Set sail for fail");
    }
  }

  @Action(path = "/failAtConstruction")
  public final class FailAtConstructionTask implements Runnable {
    public FailAtConstructionTask() {
      throw new ServiceUnavailableException("Fail at construction");
    }

    @Override
    public void run() {
      throw new AssertionError("should not get here");
    }
  }

  public class Component implements RequestComponent {
    public BumblebeeTask bumblebeeTask() {
      return bumblebeeTask;
    }

    public SlothTask slothTask() {
      return slothTask;
    }

    public SafeSlothTask safeSlothTask() {
      return safeSlothTask;
    }

    public UsersOnlyAction usersOnlyAction() {
      return usersOnlyAction;
    }

    public FailTask failTask() {
      return new FailTask();
    }

    public FailAtConstructionTask failAtConstructionTask() {
      return new FailAtConstructionTask();
    }

    // not used in this test
    @Override
    public RequestHandler<Component> requestHandler() {
      return null;
    }
  }

  @Mock
  private HttpServletRequest req;

  @Mock
  private HttpServletResponse rsp;

  @Mock
  private UserService userService;

  @Mock
  private BumblebeeTask bumblebeeTask;

  @Mock
  private SlothTask slothTask;

  @Mock
  private UsersOnlyAction usersOnlyAction;

  @Mock
  private SafeSlothTask safeSlothTask;

  private final Component component = new Component();
  private final StringWriter httpOutput = new StringWriter();
  private final Router router = new Router(ImmutableList.copyOf(Component.class.getMethods()));

  private RequestHandler<Component> getHandler(HttpServletRequest req) {
    String path = req.getRequestURI();
    String method = req.getMethod();
    Optional<Route> route = Optional.absent();
    try {
      route = router.route(path, Action.Method.valueOf(method));
    } catch (IllegalArgumentException ignored) {
      // request module returns Optional.absent() for invalid http methods.
    }
    return new RequestHandler<Component>(component, rsp, req, route);
  }
  //C component, HttpServletResponse rsp, HttpServletRequest req, Optional<Route> route)

  @Before
  public void before() throws Exception {
    inject.setStaticField(RequestHandler.class, "userService", userService);
    when(rsp.getWriter()).thenReturn(new PrintWriter(httpOutput));
  }

  @After
  public void after() throws Exception {
    verifyNoMoreInteractions(rsp, bumblebeeTask, slothTask, safeSlothTask);
  }

  @Test
  public void testHandleRequest_normalRequest_works() throws Exception {
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/bumblebee");
    getHandler(req).handleRequest();
    verifyZeroInteractions(rsp);
    verify(bumblebeeTask).run();
  }

  @Test
  public void testHandleRequest_multipleMethodMappings_works() throws Exception {
    when(req.getMethod()).thenReturn("POST");
    when(req.getRequestURI()).thenReturn("/bumblebee");
    getHandler(req).handleRequest();
    verify(bumblebeeTask).run();
  }

  @Test
  public void testHandleRequest_prefixEnabled_subpathsWork() throws Exception {
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/bumblebee/hive");
    getHandler(req).handleRequest();
    verify(bumblebeeTask).run();
  }

  @Test
  public void testHandleRequest_taskHasAutoPrintOk_printsOk() throws Exception {
    when(req.getMethod()).thenReturn("POST");
    when(req.getRequestURI()).thenReturn("/sloth");
    getHandler(req).handleRequest();
    verify(slothTask).run();
    verify(rsp).setContentType("text/plain; charset=utf-8");
    verify(rsp).getWriter();
    assertThat(httpOutput.toString()).isEqualTo("OK\n");
  }

  @Test
  public void testHandleRequest_prefixDisabled_subpathsReturn404NotFound() throws Exception {
    when(req.getMethod()).thenReturn("POST");
    when(req.getRequestURI()).thenReturn("/sloth/nest");
    getHandler(req).handleRequest();
    verify(rsp).sendError(404);
  }

  @Test
  public void testHandleRequest_taskThrowsHttpException_getsHandledByHandler() throws Exception {
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/fail");
    getHandler(req).handleRequest();
    verify(rsp).sendError(503, "Set sail for fail");
  }

  /** Test for a regression of the issue in b/21377705. */
  @Test
  public void testHandleRequest_taskThrowsHttpException_atConstructionTime_getsHandledByHandler()
      throws Exception {
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/failAtConstruction");
    getHandler(req).handleRequest();
    verify(rsp).sendError(503, "Fail at construction");
  }

  @Test
  public void testHandleRequest_notFound_returns404NotFound() throws Exception {
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/bogus");
    getHandler(req).handleRequest();
    verify(rsp).sendError(404);
  }

  @Test
  public void testHandleRequest_methodNotAllowed_returns405MethodNotAllowed() throws Exception {
    when(req.getMethod()).thenReturn("POST");
    when(req.getRequestURI()).thenReturn("/fail");
    getHandler(req).handleRequest();
    verify(rsp).sendError(405);
  }

  @Test
  public void testHandleRequest_insaneMethod_returns405MethodNotAllowed() throws Exception {
    when(req.getMethod()).thenReturn("FIREAWAY");
    when(req.getRequestURI()).thenReturn("/fail");
    getHandler(req).handleRequest();
    verify(rsp).sendError(405);
  }

  /** @see "http://www.w3.org/Protocols/rfc2616/rfc2616-sec5.html#sec5.1.1" */
  @Test
  public void testHandleRequest_lowercaseMethod_notRecognized() throws Exception {
    when(req.getMethod()).thenReturn("get");
    when(req.getRequestURI()).thenReturn("/bumblebee");
    getHandler(req).handleRequest();
    verify(rsp).sendError(405);
  }

//  @Test
//  public void testNullness() {
//    NullPointerTester tester = new NullPointerTester();
//    tester.testAllPublicStaticMethods(RequestHandler.class);
//    tester.testAllPublicInstanceMethods(handler);
//  }

  @Test
  public void testXsrfProtection_noTokenProvided_returns403Forbidden() throws Exception {
    when(req.getMethod()).thenReturn("POST");
    when(req.getRequestURI()).thenReturn("/safe-sloth");
    getHandler(req).handleRequest();
    verify(rsp).sendError(403, "Invalid X-CSRF-Token");
  }

  @Test
  public void testXsrfProtection_validTokenProvided_runsAction() throws Exception {
    when(req.getMethod()).thenReturn("POST");
    when(req.getHeader("X-CSRF-Token")).thenReturn(generateToken("vampire"));
    when(req.getRequestURI()).thenReturn("/safe-sloth");
    getHandler(req).handleRequest();
    verify(safeSlothTask).run();
  }

  @Test
  public void testXsrfProtection_tokenWithInvalidScopeProvided_returns403() throws Exception {
    when(req.getMethod()).thenReturn("POST");
    when(req.getHeader("X-CSRF-Token")).thenReturn(generateToken("blood"));
    when(req.getRequestURI()).thenReturn("/safe-sloth");
    getHandler(req).handleRequest();
    verify(rsp).sendError(403, "Invalid X-CSRF-Token");
  }

  @Test
  public void testXsrfProtection_GETMethodWithoutToken_doesntCheckToken() throws Exception {
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/safe-sloth");
    getHandler(req).handleRequest();
    verify(safeSlothTask).run();
  }

  @Test
  public void testMustBeLoggedIn_notLoggedIn_redirectsToLoginPage() throws Exception {
    when(userService.isUserLoggedIn()).thenReturn(false);
    when(userService.createLoginURL("/users-only")).thenReturn("/login");
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/users-only");
    getHandler(req).handleRequest();
    verify(rsp).setStatus(302);
    verify(rsp).setHeader("Location", "/login");
  }

  @Test
  public void testMustBeLoggedIn_loggedIn_runsAction() throws Exception {
    when(userService.isUserLoggedIn()).thenReturn(true);
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/users-only");
    getHandler(req).handleRequest();
    verify(usersOnlyAction).run();
  }
}
