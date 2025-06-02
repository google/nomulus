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
import static google.registry.testing.DatabaseHelper.loadSingleton;
import static google.registry.testing.DatabaseHelper.persistResource;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import google.registry.flows.PasswordOnlyTransportCredentials;
import google.registry.model.console.ConsoleUpdateHistory;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.RequestModule;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.ui.server.console.ConsoleEppPasswordAction.EppPasswordData;
import google.registry.util.EmailMessage;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsoleEppPasswordActionTest extends ConsoleActionBaseTestCase {
  private static String eppPostData =
      "{\"registrarId\":\"%s\",\"oldPassword\":\"%s\",\"newPassword\":\"%s\",\"newPasswordRepeat\":\"%s\"}";

  protected PasswordOnlyTransportCredentials credentials = new PasswordOnlyTransportCredentials();

  @BeforeEach
  void beforeEach() {
    Registrar registrar = Registrar.loadByRegistrarId("TheRegistrar").get();
    registrar =
        registrar
            .asBuilder()
            .setPassword("foobar")
            .build();
    persistResource(registrar);
  }

  @Test
  void testFailure_emptyParams() throws IOException {
    ConsoleEppPasswordAction action = createAction("", "", "", "");
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("Missing param(s): registrarId");
  }

  @Test
  void testFailure_passwordsDontMatch() throws IOException {
    ConsoleEppPasswordAction action =
        createAction("TheRegistrar", "oldPassword", "newPassword", "newPasswordRepeat");
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).contains("New password fields don't match");
  }

  @Test
  void testFailure_existingPasswordIncorrect() throws IOException {
    ConsoleEppPasswordAction action =
        createAction("TheRegistrar", "oldPassword", "randomPasword", "randomPasword");
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
    assertThat(response.getPayload()).contains("Registrar password is incorrect");
  }

  @Test
  void testSuccess_sendsConfirmationEmail() throws IOException, AddressException {
    ConsoleEppPasswordAction action =
        createAction("TheRegistrar", "foobar", "randomPassword", "randomPassword");
    action.run();
    verify(consoleApiParams.sendEmailUtils().gmailClient, times(1))
        .sendEmail(
            EmailMessage.newBuilder()
                .setSubject(
                    "Registrar The Registrar (TheRegistrar) updated in registry unittest"
                        + " environment")
                .setBody(
                    "The following changes were made in registry unittest environment to the"
                        + " registrar TheRegistrar by admin fte@email.tld:\n"
                        + "\n"
                        + "password: ******** -> ••••••••\n")
                .setRecipients(ImmutableList.of(new InternetAddress("notification@test.example")))
                .build());
    assertThat(response.getStatus()).isEqualTo(SC_OK);
  }

  @Test
  void testSuccess_passwordUpdated() throws IOException {
    ConsoleEppPasswordAction action =
        createAction("TheRegistrar", "foobar", "randomPassword", "randomPassword");
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertDoesNotThrow(() -> credentials.validate(loadRegistrar("TheRegistrar"), "randomPassword"));
    ConsoleUpdateHistory history = loadSingleton(ConsoleUpdateHistory.class).get();
    assertThat(history.getType()).isEqualTo(ConsoleUpdateHistory.Type.EPP_PASSWORD_UPDATE);
    assertThat(history.getDescription()).hasValue("TheRegistrar");
  }

  private ConsoleEppPasswordAction createAction(
      String registrarId, String oldPassword, String newPassword, String newPasswordRepeat)
      throws IOException {
    AuthenticatedRegistrarAccessor authenticatedRegistrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(
            ImmutableSetMultimap.of("TheRegistrar", OWNER));
    when(consoleApiParams.request().getMethod()).thenReturn(Action.Method.POST.toString());
    doReturn(
            new BufferedReader(
                new StringReader(
                    String.format(
                        eppPostData, registrarId, oldPassword, newPassword, newPasswordRepeat))))
        .when(consoleApiParams.request())
        .getReader();
    Optional<EppPasswordData> maybePasswordChangeRequest =
        ConsoleModule.provideEppPasswordChangeRequest(
            GSON, RequestModule.provideJsonBody(consoleApiParams.request(), GSON));

    return new ConsoleEppPasswordAction(
        consoleApiParams, authenticatedRegistrarAccessor, maybePasswordChangeRequest);
  }
}
