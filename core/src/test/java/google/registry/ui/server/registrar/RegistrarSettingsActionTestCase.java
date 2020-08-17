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

import static com.google.appengine.repackaged.com.google.common.collect.Iterables.getOnlyElement;
import static com.google.monitoring.metrics.contrib.LongMetricSubject.assertThat;
import static google.registry.config.RegistryConfig.getGSuiteOutgoingEmailAddress;
import static google.registry.config.RegistryConfig.getGSuiteOutgoingEmailDisplayName;
import static google.registry.request.auth.AuthenticatedRegistrarAccessor.Role.ADMIN;
import static google.registry.request.auth.AuthenticatedRegistrarAccessor.Role.OWNER;
import static google.registry.security.JsonHttpTestUtils.createJsonPayload;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.disallowRegistrarAccess;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.appengine.api.users.User;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.truth.Truth;
import google.registry.model.ofy.Ofy;
import google.registry.model.registrar.RegistrarContact;
import google.registry.request.JsonActionRunner;
import google.registry.request.JsonResponse;
import google.registry.request.ResponseImpl;
import google.registry.request.auth.AuthLevel;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import google.registry.ui.server.SendEmailUtils;
import google.registry.util.AppEngineServiceUtils;
import google.registry.util.EmailMessage;
import google.registry.util.SendEmailService;
import java.io.PrintWriter;
import java.io.StringWriter;
import javax.mail.internet.InternetAddress;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Base class for tests using {@link RegistrarSettingsAction}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public abstract class RegistrarSettingsActionTestCase {

  static final String CLIENT_ID = "TheRegistrar";

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  @Mock AppEngineServiceUtils appEngineServiceUtils;
  @Mock HttpServletRequest req;
  @Mock HttpServletResponse rsp;
  @Mock SendEmailService emailService;

  final RegistrarSettingsAction action = new RegistrarSettingsAction();
  private final StringWriter writer = new StringWriter();
  final FakeClock clock = new FakeClock(DateTime.parse("2014-01-01T00:00:00Z"));

  RegistrarContact techContact;

  @BeforeEach
  public void beforeEachRegistrarSettingsActionTestCase() throws Exception {
    // Registrar "TheRegistrar" has access to TLD "currenttld" but not to "newtld".
    createTlds("currenttld", "newtld");
    disallowRegistrarAccess(CLIENT_ID, "newtld");

    // Add a technical contact to the registrar (in addition to the default admin contact created by
    // AppEngineRule).
    techContact =
        getOnlyElement(loadRegistrar(CLIENT_ID).getContactsOfType(RegistrarContact.Type.TECH));

    action.registrarAccessor = null;
    action.appEngineServiceUtils = appEngineServiceUtils;
    when(appEngineServiceUtils.getCurrentVersionHostname("backend")).thenReturn("backend.hostname");
    action.jsonActionRunner =
        new JsonActionRunner(ImmutableMap.of(), new JsonResponse(new ResponseImpl(rsp)));
    action.sendEmailUtils =
        new SendEmailUtils(
            getGSuiteOutgoingEmailAddress(),
            getGSuiteOutgoingEmailDisplayName(),
            ImmutableList.of("notification@test.example", "notification2@test.example"),
            emailService);
    action.registrarConsoleMetrics = new RegistrarConsoleMetrics();
    action.authResult =
        AuthResult.create(
            AuthLevel.USER,
            UserAuthInfo.create(new User("user@email.com", "email.com", "12345"), false));
    inject.setStaticField(Ofy.class, "clock", clock);
    when(req.getMethod()).thenReturn("POST");
    when(rsp.getWriter()).thenReturn(new PrintWriter(writer));
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(createJsonPayload(ImmutableMap.of("op", "read")));
    // We set the default to a user with access, as that's the most common test case. When we want
    // to specifically check a user without access, we can switch user for that specific test.
    setUserWithAccess();
    RegistrarConsoleMetrics.settingsRequestMetric.reset();
  }

  @AfterEach
  public void afterEach() {
    assertThat(RegistrarConsoleMetrics.settingsRequestMetric).hasNoOtherValues();
  }

  void assertMetric(String clientId, String op, String roles, String status) {
    assertThat(RegistrarConsoleMetrics.settingsRequestMetric)
        .hasValueForLabels(1, clientId, op, roles, status);
    RegistrarConsoleMetrics.settingsRequestMetric.reset(clientId, op, roles, status);
  }

  /** Sets registrarAccessor.getRegistrar to succeed for CLIENT_ID only. */
  private void setUserWithAccess() {
    action.registrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(
            ImmutableSetMultimap.of(CLIENT_ID, OWNER));
  }

  /** Sets registrarAccessor.getRegistrar to always fail. */
  void setUserWithoutAccess() {
    action.registrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(ImmutableSetMultimap.of());
  }

  /**
   * Sets registrarAccessor.getAllClientIdWithRoles to return a map with admin role for CLIENT_ID
   */
  void setUserAdmin() {
    action.registrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(
            ImmutableSetMultimap.of(CLIENT_ID, ADMIN));
  }

  /** Verifies that the original contact of TheRegistrar is among those notified of a change. */
  void verifyNotificationEmailsSent() throws Exception {
    ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
    verify(emailService).sendEmail(captor.capture());
    Truth.assertThat(captor.getValue().recipients())
        .containsExactly(
            new InternetAddress("notification@test.example"),
            new InternetAddress("notification2@test.example"),
            new InternetAddress("johndoe@theregistrar.com"));
  }
}
