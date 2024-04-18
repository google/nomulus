// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.console.settings;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.registrar.RegistrarPocBase.Type.WHOIS;
import static google.registry.testing.DatabaseHelper.createAdminUser;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.Action;
import google.registry.request.RequestModule;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.FakeResponse;
import google.registry.ui.server.registrar.ConsoleApiParams;
import google.registry.ui.server.registrar.RegistrarConsoleModule;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link google.registry.ui.server.console.settings.ContactAction}. */
class ContactActionTest {
  private static String jsonRegistrar1 =
      "{\"name\":\"Test Registrar 1\","
          + "\"emailAddress\":\"test.registrar1@example.com\","
          + "\"registrarId\":\"registrarId\","
          + "\"phoneNumber\":\"+1.9999999999\",\"faxNumber\":\"+1.9999999991\","
          + "\"types\":[\"WHOIS\"],\"visibleInWhoisAsAdmin\":true,"
          + "\"visibleInWhoisAsTech\":false,\"visibleInDomainWhoisAsAbuse\":false}";

  private static String jsonRegistrar2 =
      "{\"name\":\"Test Registrar 2\","
          + "\"emailAddress\":\"test.registrar2@example.com\","
          + "\"registrarId\":\"registrarId\","
          + "\"phoneNumber\":\"+1.1234567890\",\"faxNumber\":\"+1.1234567891\","
          + "\"types\":[\"WHOIS\"],\"visibleInWhoisAsAdmin\":true,"
          + "\"visibleInWhoisAsTech\":false,\"visibleInDomainWhoisAsAbuse\":false}";

  private Registrar testRegistrar;
  private ConsoleApiParams consoleApiParams;
  private RegistrarPoc testRegistrarPoc;
  private static final Gson GSON = RequestModule.provideGson();

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    testRegistrar = saveRegistrar("registrarId");
    testRegistrarPoc =
        new RegistrarPoc.Builder()
            .setRegistrar(testRegistrar)
            .setName("Test Registrar 1")
            .setEmailAddress("test.registrar1@example.com")
            .setPhoneNumber("+1.9999999999")
            .setFaxNumber("+1.9999999991")
            .setTypes(ImmutableSet.of(WHOIS))
            .setVisibleInWhoisAsAdmin(true)
            .setVisibleInWhoisAsTech(false)
            .setVisibleInDomainWhoisAsAbuse(false)
            .build();
  }

  @Test
  void testSuccess_getContactInfo() throws IOException {
    insertInDb(testRegistrarPoc);
    ContactAction action =
        createAction(
            Action.Method.GET,
            AuthResult.createUser(createAdminUser("email@email.com")),
            testRegistrar.getRegistrarId(),
            null);
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_OK);
    assertThat(((FakeResponse) consoleApiParams.response()).getPayload())
        .isEqualTo("[" + jsonRegistrar1 + "]");
  }

  @Test
  void testSuccess_onlyContactsWithNonEmptyType() throws IOException {
    testRegistrarPoc = testRegistrarPoc.asBuilder().setTypes(ImmutableSet.of()).build();
    insertInDb(testRegistrarPoc);
    ContactAction action =
        createAction(
            Action.Method.GET,
            AuthResult.createUser(createAdminUser("email@email.com")),
            testRegistrar.getRegistrarId(),
            null);
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_OK);
    assertThat(((FakeResponse) consoleApiParams.response()).getPayload()).isEqualTo("[]");
  }

  @Test
  void testSuccess_postCreateContactInfo() throws IOException {
    ContactAction action =
        createAction(
            Action.Method.POST,
            AuthResult.createUser(createAdminUser("email@email.com")),
            testRegistrar.getRegistrarId(),
            "[" + jsonRegistrar1 + "," + jsonRegistrar2 + "]");
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_OK);
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .map(r -> r.getName())
                .collect(toImmutableList()))
        .containsExactly("Test Registrar 1", "Test Registrar 2");
  }

  @Test
  void testSuccess_postUpdateContactInfo() throws IOException {
    testRegistrarPoc = testRegistrarPoc.asBuilder().setEmailAddress("incorrect@email.com").build();
    insertInDb(testRegistrarPoc);
    ContactAction action =
        createAction(
            Action.Method.POST,
            AuthResult.createUser(createAdminUser("email@email.com")),
            testRegistrar.getRegistrarId(),
            "[" + jsonRegistrar1 + "," + jsonRegistrar2 + "]");
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_OK);
    HashMap<String, String> testResult = new HashMap<>();
    loadAllOf(RegistrarPoc.class).stream()
        .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
        .forEach(r -> testResult.put(r.getName(), r.getEmailAddress()));
    assertThat(testResult)
        .containsExactly(
            "Test Registrar 1",
            "test.registrar1@example.com",
            "Test Registrar 2",
            "test.registrar2@example.com");
  }

  @Test
  void testSuccess_postDeleteContactInfo() throws IOException {
    insertInDb(testRegistrarPoc);
    ContactAction action =
        createAction(
            Action.Method.POST,
            AuthResult.createUser(createAdminUser("email@email.com")),
            testRegistrar.getRegistrarId(),
            "[" + jsonRegistrar2 + "]");
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_OK);
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .map(r -> r.getName())
                .collect(toImmutableList()))
        .containsExactly("Test Registrar 2");
  }

  @Test
  void testFailure_postDeleteContactInfo_missingPermission() throws IOException {
    insertInDb(testRegistrarPoc);
    ContactAction action =
        createAction(
            Action.Method.POST,
            AuthResult.createUser(
                new User.Builder()
                    .setEmailAddress("email@email.com")
                    .setUserRoles(
                        new UserRoles.Builder()
                            .setRegistrarRoles(
                                ImmutableMap.of(
                                    testRegistrar.getRegistrarId(), RegistrarRole.ACCOUNT_MANAGER))
                            .build())
                    .build()),
            testRegistrar.getRegistrarId(),
            "[" + jsonRegistrar2 + "]");
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  private ContactAction createAction(
      Action.Method method, AuthResult authResult, String registrarId, String contacts)
      throws IOException {
    consoleApiParams = ConsoleApiParamsUtils.createFake(authResult);
    when(consoleApiParams.request().getMethod()).thenReturn(method.toString());
    if (method.equals(Action.Method.GET)) {
      return new ContactAction(consoleApiParams, GSON, registrarId, Optional.empty());
    } else {
      doReturn(new BufferedReader(new StringReader(contacts)))
          .when(consoleApiParams.request())
          .getReader();
      Optional<ImmutableSet<RegistrarPoc>> maybeContacts =
          RegistrarConsoleModule.provideContacts(
              GSON, RequestModule.provideJsonBody(consoleApiParams.request(), GSON));
      return new ContactAction(consoleApiParams, GSON, registrarId, maybeContacts);
    }
  }
}
