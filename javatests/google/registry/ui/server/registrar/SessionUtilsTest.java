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

package google.registry.ui.server.registrar;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.AppEngineRule.THE_REGISTRAR_GAE_USER_ID;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserService;
import com.google.common.testing.NullPointerTester;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.testing.AppEngineRule;
import google.registry.testing.ExceptionRule;
import google.registry.testing.InjectRule;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

/** Unit tests for {@link SessionUtils}. */
@RunWith(MockitoJUnitRunner.class)
public class SessionUtilsTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  @Rule
  public final InjectRule inject = new InjectRule();

  @Mock
  private UserService userService;

  @Mock
  private HttpServletRequest req;

  @Mock
  private HttpServletResponse rsp;

  @Mock
  private HttpSession session;

  private SessionUtils sessionUtils;
  private final User jart = new User("jart@google.com", "google.com", THE_REGISTRAR_GAE_USER_ID);
  private final User bozo = new User("bozo@bing.com", "bing.com", "badGaeUserId");

  @Before
  public void before() throws Exception {
    sessionUtils = new SessionUtils(userService);
    when(req.getSession()).thenReturn(session);
  }

  @Test
  public void testCheckRegistrarConsoleLogin_authedButNoSession_createsSession() throws Exception {
    when(userService.getCurrentUser()).thenReturn(jart);
    assertThat(sessionUtils.checkRegistrarConsoleLogin(req)).isTrue();
    verify(session).setAttribute(eq("clientId"), eq("TheRegistrar"));
  }

  @Test
  public void testCheckRegistrarConsoleLogin_authedWithValidSession_doesNothing() throws Exception {
    when(session.getAttribute("clientId")).thenReturn("TheRegistrar");
    when(userService.getCurrentUser()).thenReturn(jart);
    assertThat(sessionUtils.checkRegistrarConsoleLogin(req)).isTrue();
    verify(session).getAttribute("clientId");
    verifyNoMoreInteractions(session);
  }

  @Test
  public void testCheckRegistrarConsoleLogin_sessionRevoked_invalidates() throws Exception {
    RegistrarContact.updateContacts(
        Registrar.loadByClientId("TheRegistrar"),
        new java.util.HashSet<RegistrarContact>());
    when(session.getAttribute("clientId")).thenReturn("TheRegistrar");
    when(userService.getCurrentUser()).thenReturn(jart);
    assertThat(sessionUtils.checkRegistrarConsoleLogin(req)).isFalse();
    verify(session).invalidate();
  }

  @Test
  public void testCheckRegistrarConsoleLogin_notLoggedIn_throwsIse() throws Exception {
    thrown.expect(IllegalStateException.class);
    assertThat(sessionUtils.checkRegistrarConsoleLogin(req)).isNull();
  }

  @Test
  public void testCheckRegistrarConsoleLogin_notAllowed_returnsFalse() throws Exception {
    when(userService.getCurrentUser()).thenReturn(bozo);
    assertThat(sessionUtils.checkRegistrarConsoleLogin(req)).isFalse();
  }

  @Test
  public void testNullness() throws Exception {
    new NullPointerTester()
        .setDefault(HttpServletRequest.class, req)
        .setDefault(HttpServletResponse.class, rsp)
        .testAllPublicStaticMethods(SessionUtils.class);
  }
}
