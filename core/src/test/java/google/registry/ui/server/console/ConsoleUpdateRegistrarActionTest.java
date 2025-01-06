// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.model.registrar.RegistrarPocBase.Type.WHOIS;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.loadSingleton;
import static google.registry.testing.DatabaseHelper.persistResource;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.RegistrarUpdateHistory;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarBase;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.Action;
import google.registry.request.RequestModule;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.FakeResponse;
import google.registry.testing.SystemPropertyExtension;
import google.registry.tools.GsonUtils;
import google.registry.util.EmailMessage;
import google.registry.util.RegistryEnvironment;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link google.registry.ui.server.console.ConsoleUpdateRegistrarAction}. */
class ConsoleUpdateRegistrarActionTest {
  private static final Gson GSON = GsonUtils.provideGson();

  private ConsoleApiParams consoleApiParams;
  private FakeResponse response;

  private Registrar registrar;

  private User user;

  private static String registrarPostData =
      "{\"registrarId\":\"%s\",\"allowedTlds\":[%s],\"registryLockAllowed\":%s}";

  @RegisterExtension
  @Order(Integer.MAX_VALUE)
  final SystemPropertyExtension systemPropertyExtension = new SystemPropertyExtension();

  @BeforeEach
  void beforeEach() throws Exception {
    createTlds("app", "dev");
    registrar = Registrar.loadByRegistrarId("TheRegistrar").get();
    persistResource(
        registrar
            .asBuilder()
            .setType(RegistrarBase.Type.REAL)
            .setAllowedTlds(ImmutableSet.of())
            .setRegistryLockAllowed(false)
            .build());
    user =
        persistResource(
            new User.Builder()
                .setEmailAddress("user@registrarId.com")
                .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build())
                .build());
    consoleApiParams = createParams();
  }

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @Test
  void testSuccess_updatesRegistrar() throws IOException {
    var action = createAction(String.format(registrarPostData, "TheRegistrar", "app, dev", false));
    action.run();
    Registrar newRegistrar = Registrar.loadByRegistrarId("TheRegistrar").get();
    assertThat(newRegistrar.getAllowedTlds()).containsExactly("app", "dev");
    assertThat(newRegistrar.isRegistryLockAllowed()).isFalse();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_OK);
    assertAboutImmutableObjects()
        .that(newRegistrar)
        .hasFieldsEqualTo(loadSingleton(RegistrarUpdateHistory.class).get().getRegistrar());
  }

  @Test
  void testFails_missingWhoisContact() throws IOException {
    RegistryEnvironment.PRODUCTION.setup(systemPropertyExtension);
    var action = createAction(String.format(registrarPostData, "TheRegistrar", "app, dev", false));
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat((String) ((FakeResponse) consoleApiParams.response()).getPayload())
        .contains("Cannot modify allowed TLDs if there is no WHOIS abuse contact set");
  }

  @Test
  void testSuccess_presentWhoisContact() throws IOException {
    RegistryEnvironment.PRODUCTION.setup(systemPropertyExtension);
    RegistrarPoc contact =
        new RegistrarPoc.Builder()
            .setRegistrar(registrar)
            .setName("Test Registrar 1")
            .setEmailAddress("test.registrar1@example.com")
            .setPhoneNumber("+1.9999999999")
            .setFaxNumber("+1.9999999991")
            .setTypes(ImmutableSet.of(WHOIS))
            .setVisibleInWhoisAsAdmin(true)
            .setVisibleInWhoisAsTech(true)
            .setVisibleInDomainWhoisAsAbuse(true)
            .build();
    persistResource(contact);
    var action = createAction(String.format(registrarPostData, "TheRegistrar", "app, dev", false));
    action.run();
    Registrar newRegistrar = Registrar.loadByRegistrarId("TheRegistrar").get();
    assertThat(newRegistrar.getAllowedTlds()).containsExactly("app", "dev");
    assertThat(newRegistrar.isRegistryLockAllowed()).isFalse();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_OK);
  }

  @Test
  void testSuccess_sendsEmail() throws AddressException, IOException {
    var action = createAction(String.format(registrarPostData, "TheRegistrar", "app, dev", false));
    action.run();
    verify(consoleApiParams.sendEmailUtils().gmailClient, times(1))
        .sendEmail(
            EmailMessage.newBuilder()
                .setSubject(
                    "Registrar The Registrar (TheRegistrar) updated in registry unittest"
                        + " environment")
                .setBody(
                    "The following changes were made in registry unittest environment to the"
                        + " registrar TheRegistrar by user user@registrarId.com:\n"
                        + "\n"
                        + "allowedTlds: null -> [app, dev]\n")
                .setRecipients(ImmutableList.of(new InternetAddress("notification@test.example")))
                .build());
  }

  private ConsoleApiParams createParams() {
    AuthResult authResult = AuthResult.createUser(user);
    return ConsoleApiParamsUtils.createFake(authResult);
  }

  ConsoleUpdateRegistrarAction createAction(String requestData) throws IOException {
    when(consoleApiParams.request().getMethod()).thenReturn(Action.Method.POST.toString());
    doReturn(new BufferedReader(new StringReader(requestData)))
        .when(consoleApiParams.request())
        .getReader();
    Optional<Registrar> maybeRegistrarUpdateData =
        ConsoleModule.provideRegistrar(
            GSON, RequestModule.provideJsonBody(consoleApiParams.request(), GSON));
    return new ConsoleUpdateRegistrarAction(consoleApiParams, maybeRegistrarUpdateData);
  }
}
