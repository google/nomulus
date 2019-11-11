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

package google.registry.ui.server.registrar;

import static com.google.common.net.HttpHeaders.LOCATION;
import static com.google.common.truth.Truth.assertThat;
import static com.google.monitoring.metrics.contrib.LongMetricSubject.assertThat;
import static google.registry.request.auth.AuthenticatedRegistrarAccessor.Role.ADMIN;
import static google.registry.request.auth.AuthenticatedRegistrarAccessor.Role.OWNER;
import static javax.servlet.http.HttpServletResponse.SC_MOVED_TEMPORARILY;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserServiceFactory;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.net.MediaType;
import google.registry.request.Action.Method;
import google.registry.request.auth.AuthLevel;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.UserAuthInfo;
import google.registry.security.XsrfTokenManager;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.UserInfo;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ConsoleUiAction}. */
@RunWith(JUnit4.class)
public class ConsoleUiActionTest {

  @Rule
  public final AppEngineRule appEngineRule =
      AppEngineRule.builder()
          .withDatastore()
          .withUserService(UserInfo.create("marla.singer@example.com", "12345"))
          .build();

  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final FakeResponse response = new FakeResponse();
  private final ConsoleUiAction action = new ConsoleUiAction();
  private final User user = new User("marla.singer@example.com", "gmail.com", "12345");

  @Before
  public void setUp() {
    action.enabled = true;
    action.logoFilename = "logo.png";
    action.productName = "Nomulus";
    action.integrationEmail = "integration@example.com";
    action.supportEmail = "support@example.com";
    action.announcementsEmail = "announcements@example.com";
    action.supportPhoneNumber = "1 (888) 555 0123";
    action.technicalDocsUrl = "http://example.com/technical-docs";
    action.req = request;
    action.response = response;
    action.registrarConsoleMetrics = new RegistrarConsoleMetrics();
    action.userService = UserServiceFactory.getUserService();
    action.xsrfTokenManager = new XsrfTokenManager(new FakeClock(), action.userService);
    action.method = Method.GET;
    action.paramClientId = Optional.empty();
    action.authResult = AuthResult.create(AuthLevel.USER, UserAuthInfo.create(user, false));
    action.analyticsConfig = ImmutableMap.of("googleAnalyticsId", "sampleId");

    action.registrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(
            ImmutableSetMultimap.of(
                "TheRegistrar", OWNER,
                "NewRegistrar", OWNER,
                "NewRegistrar", ADMIN,
                "AdminRegistrar", ADMIN));
    RegistrarConsoleMetrics.consoleRequestMetric.reset();
  }

  @After
  public void tearDown() {
    assertThat(RegistrarConsoleMetrics.consoleRequestMetric).hasNoOtherValues();
  }

  public void assertMetric(String clientId, String explicitClientId, String roles, String status) {
    assertThat(RegistrarConsoleMetrics.consoleRequestMetric)
        .hasValueForLabels(1, clientId, explicitClientId, roles, status);
    RegistrarConsoleMetrics.consoleRequestMetric.reset(clientId, explicitClientId, roles, status);
  }

  @Test
  public void testWebPage_disallowsIframe() {
    action.run();
    assertThat(response.getHeaders()).containsEntry("X-Frame-Options", "SAMEORIGIN");
    assertMetric("TheRegistrar", "false", "[OWNER]", "SUCCESS");
  }

  @Test
  public void testWebPage_setsHtmlUtf8ContentType() {
    action.run();
    assertThat(response.getContentType()).isEqualTo(MediaType.HTML_UTF_8);
    assertMetric("TheRegistrar", "false", "[OWNER]", "SUCCESS");
  }

  @Test
  public void testWebPage_containsUserNickname() {
    action.run();
    assertThat(response.getPayload()).contains("marla.singer");
    assertMetric("TheRegistrar", "false", "[OWNER]", "SUCCESS");
  }

  @Test
  public void testWebPage_containsGoogleAnalyticsId() {
    action.run();
    assertThat(response.getPayload()).contains("gtag('config', 'sampleId')");
    assertMetric("TheRegistrar", "false", "[OWNER]", "SUCCESS");
  }

  @Test
  public void testUserHasAccessAsTheRegistrar_showsRegistrarConsole() {
    action.run();
    assertThat(response.getPayload()).contains("Registrar Console");
    assertThat(response.getPayload()).contains("reg-content-and-footer");
    assertMetric("TheRegistrar", "false", "[OWNER]", "SUCCESS");
  }

  @Test
  public void testConsoleDisabled_showsDisabledPage() {
    action.enabled = false;
    action.run();
    assertThat(response.getPayload()).contains("<h1>Console is disabled</h1>");
    assertThat(response.getPayload()).contains("gtag('config', 'sampleId')");
  }

  @Test
  public void testUserDoesntHaveAccessToAnyRegistrar_showsWhoAreYouPage() {
    action.registrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(ImmutableSetMultimap.of());
    action.run();
    assertThat(response.getPayload()).contains("<h1>You need permission</h1>");
    assertThat(response.getPayload()).contains("not associated with Nomulus.");
    assertThat(response.getPayload()).contains("gtag('config', 'sampleId')");
    assertMetric("<null>", "false", "[]", "FORBIDDEN");
  }

  @Test
  public void testNoUser_redirect() {
    when(request.getRequestURI()).thenReturn("/test");
    action.authResult = AuthResult.NOT_AUTHENTICATED;
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_MOVED_TEMPORARILY);
    assertThat(response.getHeaders().get(LOCATION)).isEqualTo("/_ah/login?continue=%2Ftest");
  }

  @Test
  public void testNoUserInformationAtAll_redirectToRoot() {
    when(request.getRequestURI()).thenThrow(new IllegalArgumentException());
    action.authResult = AuthResult.NOT_AUTHENTICATED;
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_MOVED_TEMPORARILY);
    assertThat(response.getHeaders().get(LOCATION)).isEqualTo("/");
  }

  @Test
  public void testSettingClientId_notAllowed_showsNeedPermissionPage() {
    // Behaves the same way if fakeRegistrar exists, but we don't have access to it
    action.paramClientId = Optional.of("fakeRegistrar");
    action.run();
    assertThat(response.getPayload()).contains("<h1>You need permission</h1>");
    assertThat(response.getPayload()).contains("not associated with the registrar fakeRegistrar.");
    assertThat(response.getPayload()).contains("gtag('config', 'sampleId')");
    assertMetric("fakeRegistrar", "true", "[]", "FORBIDDEN");
  }

  @Test
  public void testSettingClientId_allowed_showsRegistrarConsole() {
    action.paramClientId = Optional.of("NewRegistrar");
    action.run();
    assertThat(response.getPayload()).contains("Registrar Console");
    assertThat(response.getPayload()).contains("reg-content-and-footer");
    assertThat(response.getPayload()).contains("gtag('config', 'sampleId')");
    assertMetric("NewRegistrar", "true", "[OWNER, ADMIN]", "SUCCESS");
  }

  @Test
  public void testUserHasAccessAsTheRegistrar_showsClientIdChooser() {
    action.run();
    assertThat(response.getPayload()).contains("<option value=\"TheRegistrar\" selected>");
    assertThat(response.getPayload()).contains("<option value=\"NewRegistrar\">");
    assertThat(response.getPayload()).contains("<option value=\"AdminRegistrar\">");
    assertThat(response.getPayload()).contains("gtag('config', 'sampleId')");
    assertMetric("TheRegistrar", "false", "[OWNER]", "SUCCESS");
  }
}
