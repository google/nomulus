// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.request.auth.AuthenticatedRegistrarAccessor.Role.OWNER;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.http.HttpStatusCodes;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.gson.Gson;
import google.registry.flows.PasswordOnlyTransportCredentials;
import google.registry.groups.GmailClient;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrar.Registrar;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.Action;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.testing.ConsoleApiParamsUtil;
import google.registry.testing.FakeResponse;
import google.registry.tools.GsonUtils;
import google.registry.ui.server.registrar.ConsoleApiParams;
import google.registry.util.EmailMessage;
import jakarta.servlet.http.Cookie;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ConsoleEppPasswordActionTest {
  private static final Gson GSON = GsonUtils.provideGson();
  private ConsoleApiParams consoleApiParams;
  protected PasswordOnlyTransportCredentials credentials = new PasswordOnlyTransportCredentials();
  private FakeResponse response;
  private GmailClient gmailClient = mock(GmailClient.class);

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    Registrar registrar = persistNewRegistrar("registrarId");
    registrar =
        registrar
            .asBuilder()
            .setType(Registrar.Type.TEST)
            .setIanaIdentifier(null)
            .setPassword("foobar")
            .setEmailAddress("testEmail@google.com")
            .build();
    persistResource(registrar);
  }

  @Test
  void testFailure_emptyParams() {
    ConsoleEppPasswordAction action = createAction();
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus())
        .isEqualTo(HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
    assertThat(((FakeResponse) consoleApiParams.response()).getPayload())
        .isEqualTo("Missing parameter: registrarId");
  }

  @Test
  void testFailure_passwordsDontMatch() {
    ConsoleEppPasswordAction action = createAction();
    setParams(
        ImmutableMap.of(
            "registrarId",
            "registrarId",
            "oldPassword",
            "oldPassword",
            "newPassword",
            "newPassword",
            "newPasswordRepeat",
            "newPasswordRepeat"));
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus())
        .isEqualTo(HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
    assertThat(((FakeResponse) consoleApiParams.response()).getPayload())
        .contains("New password fields don't match");
  }

  @Test
  void testFailure_existingPasswordIncorrect() {
    ConsoleEppPasswordAction action = createAction();
    setParams(
        ImmutableMap.of(
            "registrarId",
            "registrarId",
            "oldPassword",
            "oldPassword",
            "newPassword",
            "randomPasword",
            "newPasswordRepeat",
            "randomPasword"));
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus())
        .isEqualTo(HttpStatusCodes.STATUS_CODE_FORBIDDEN);
    assertThat(((FakeResponse) consoleApiParams.response()).getPayload())
        .contains("Registrar password is incorrect");
  }

  @Test
  void testSuccess_sendsConfirmationEmail() throws AddressException {
    ConsoleEppPasswordAction action = createAction();
    setParams(
        ImmutableMap.of(
            "registrarId",
            "registrarId",
            "oldPassword",
            "foobar",
            "newPassword",
            "randomPassword",
            "newPasswordRepeat",
            "randomPassword"));
    action.run();
    verify(gmailClient, times(1))
        .sendEmail(
            EmailMessage.create(
                "EPP password update confirmation",
                "Dear registrarId name,\n"
                    + "This is to confirm that your account password has been changed.",
                new InternetAddress("testEmail@google.com")));
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus())
        .isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
  }

  @Test
  void testSuccess_passwordUpdated() throws AddressException {
    ConsoleEppPasswordAction action = createAction();
    setParams(
        ImmutableMap.of(
            "registrarId",
            "registrarId",
            "oldPassword",
            "foobar",
            "newPassword",
            "randomPassword",
            "newPasswordRepeat",
            "randomPassword"));
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus())
        .isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    assertDoesNotThrow(
        () -> {
          credentials.validate(loadRegistrar("registrarId"), "randomPassword");
        });
  }

  private void setParams(ImmutableMap<String, String> params) {
    params.entrySet().stream()
        .forEach(
            entry -> {
              when(consoleApiParams.request().getParameter(entry.getKey()))
                  .thenReturn(entry.getValue());
            });
  }

  private ConsoleEppPasswordAction createAction() {
    response = new FakeResponse();
    User user =
        new User.Builder()
            .setEmailAddress("email@email.com")
            .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build())
            .build();

    AuthResult authResult = AuthResult.createUser(user);
    consoleApiParams = ConsoleApiParamsUtil.createFake(authResult);
    AuthenticatedRegistrarAccessor authenticatedRegistrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(
            ImmutableSetMultimap.of("registrarId", OWNER));
    Cookie cookie =
        new Cookie(
            consoleApiParams.xsrfTokenManager().X_CSRF_TOKEN,
            consoleApiParams.xsrfTokenManager().generateToken("email@email.com"));
    when(consoleApiParams.request().getMethod()).thenReturn(Action.Method.POST.toString());
    when(consoleApiParams.request().getCookies()).thenReturn(new Cookie[] {cookie});

    return new ConsoleEppPasswordAction(
        consoleApiParams, authenticatedRegistrarAccessor, gmailClient);
  }
}
