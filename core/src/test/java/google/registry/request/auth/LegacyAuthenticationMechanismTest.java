// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserService;
import google.registry.security.XsrfTokenManager;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link LegacyAuthenticationMechanism}. */
@ExtendWith(MockitoExtension.class)
final class LegacyAuthenticationMechanismTest {

  @RegisterExtension
  final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  @Mock private UserService userService;
  @Mock private HttpServletRequest req;

  private final FakeClock clock = new FakeClock();
  private XsrfTokenManager xsrfTokenManager;
  private LegacyAuthenticationMechanism legacyAuthenticationMechanism;
  private String goodToken;

  @BeforeEach
  void beforeEach() {
    xsrfTokenManager = new XsrfTokenManager(clock, userService);
    legacyAuthenticationMechanism =
        new LegacyAuthenticationMechanism(userService, xsrfTokenManager);
    when(userService.getCurrentUser()).thenReturn(new User("email@example.com", "example.com"));
    when(userService.isUserAdmin()).thenReturn(false);
    goodToken = xsrfTokenManager.generateToken("email@example.com");
  }

  @AfterEach
  void afterEach() {
    // Make sure we didn't use getParameter or getInputStream or any of the other "with side
    // effects" getters unexpectedly. But allow "no side effect" getters.
    //
    // Unfortunately HttpServletRequest doesn't document well which getters "have side effects". It
    // does explicitly state getReader and getInputStream, and that getParameter can also interfere
    // with them, but it doesn't say anything about getParameterNames, getParameterValues,
    // getParameterMap - even though I'm pretty sure they are similar to getParameter in that
    // effect.
    //
    // Feel free to add other "no side effect" functions with atLeast(0) to exempt them from the
    // verifyNoMoreInteractions
    verify(req, atLeast(0)).getMethod();
    verify(req, atLeast(0)).getHeader(any());
    verifyNoMoreInteractions(req);
  }

  @Test
  @MockitoSettings(strictness = Strictness.LENIENT)
  void testAuthenticate_notLoggedIn() {
    when(userService.isUserLoggedIn()).thenReturn(false);
    assertThat(legacyAuthenticationMechanism.authenticate(req).authLevel())
        .isEqualTo(AuthLevel.NONE);
  }

  @Test
  void testAuthenticate_loggedInSafeMethod_get() {
    when(userService.isUserLoggedIn()).thenReturn(true);
    when(req.getMethod()).thenReturn("GET");
    assertThat(legacyAuthenticationMechanism.authenticate(req).authLevel())
        .isEqualTo(AuthLevel.USER);
  }

  @Test
  void testAuthenticate_loggedInSafeMethod_head() {
    when(userService.isUserLoggedIn()).thenReturn(true);
    when(req.getMethod()).thenReturn("HEAD");
    assertThat(legacyAuthenticationMechanism.authenticate(req).authLevel())
        .isEqualTo(AuthLevel.USER);
  }

  @Test
  @MockitoSettings(strictness = Strictness.LENIENT)
  void testAuthenticate_loggedInUnsafeMethod_post_noXsrfToken() {
    when(userService.isUserLoggedIn()).thenReturn(true);
    when(req.getMethod()).thenReturn("POST");
    assertThat(legacyAuthenticationMechanism.authenticate(req).authLevel())
        .isEqualTo(AuthLevel.NONE);

    // Make sure we looked for the token in all relevant places before giving up
    verify(req).getHeader("X-CSRF-Token");
    verify(req).getParameter("xsrfToken");
  }

  @Test
  void testAuthenticate_loggedInUnsafeMethod_post_goodTokenInHeader() {
    when(userService.isUserLoggedIn()).thenReturn(true);
    when(req.getMethod()).thenReturn("POST");
    when(req.getHeader("X-CSRF-Token")).thenReturn(goodToken);
    assertThat(legacyAuthenticationMechanism.authenticate(req).authLevel())
        .isEqualTo(AuthLevel.USER);

    // Make sure we didn't call getParameter (we already verify it in the @After, but we're doing it
    // here explicitly as well for clarity, since this is important in this test)
    verify(req, times(0)).getParameter(any());
  }

  @Test
  @MockitoSettings(strictness = Strictness.LENIENT)
  void testAuthenticate_loggedInUnsafeMethod_post_badTokenInHeader() {
    when(userService.isUserLoggedIn()).thenReturn(true);
    when(req.getMethod()).thenReturn("POST");
    when(req.getHeader("X-CSRF-Token")).thenReturn("bad");
    assertThat(legacyAuthenticationMechanism.authenticate(req).authLevel())
        .isEqualTo(AuthLevel.NONE);

    // Make sure we didn't call getParameter (we already verify it in the @After, but we're doing it
    // here explicitly as well for clarity, since this is important in this test)
    verify(req, times(0)).getParameter(any());
  }

  @Test
  void testAuthenticate_loggedInUnsafeMethod_post_goodTokenInParam() {
    when(userService.isUserLoggedIn()).thenReturn(true);
    when(req.getMethod()).thenReturn("POST");
    when(req.getParameter("xsrfToken")).thenReturn(goodToken);
    assertThat(legacyAuthenticationMechanism.authenticate(req).authLevel())
        .isEqualTo(AuthLevel.USER);

    // we allow getParameter to be called in this case (we verify it so it's not caught in the
    // @After's verifyNoMoreInteractions)
    verify(req).getParameter("xsrfToken");
  }

  @Test
  @MockitoSettings(strictness = Strictness.LENIENT)
  void testAuthenticate_loggedInUnsafeMethod_post_badTokenInParam() {
    when(userService.isUserLoggedIn()).thenReturn(true);
    when(req.getMethod()).thenReturn("POST");
    when(req.getParameter("xsrfToken")).thenReturn("bad");
    assertThat(legacyAuthenticationMechanism.authenticate(req).authLevel())
        .isEqualTo(AuthLevel.NONE);

    // we allow getParameter to be called in this case (we verify it so it's not caught in the
    // @After's verifyNoMoreInteractions)
    verify(req).getParameter("xsrfToken");
  }
}
