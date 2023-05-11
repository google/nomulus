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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.registrar.RegistrarPoc.Type.WHOIS;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.client.http.HttpStatusCodes;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.Action;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthSettings.AuthLevel;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.FakeResponse;
import google.registry.ui.server.registrar.RegistrarConsoleModule;
import google.registry.util.UtilsModule;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
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
  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private RegistrarPoc testRegistrarPoc;
  private static final Gson GSON = UtilsModule.provideGson();
  private static final FakeResponse RESPONSE = new FakeResponse();

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
  void testSuccess_getContactInfo() {
    insertInDb(testRegistrarPoc);
    ContactAction action =
        createAction(
            Action.Method.GET,
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(
                    createUser(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build()))),
            "registrarId",
            null);
    action.run();
    assertThat(RESPONSE.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    assertThat(RESPONSE.getPayload()).isEqualTo("[" + jsonRegistrar1 + "]");
  }

  @Test
  void testSuccess_postCreateContactInfo() {
    ContactAction action =
        createAction(
            Action.Method.POST,
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(
                    createUser(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build()))),
            "registrarId",
            "[" + jsonRegistrar1 + "," + jsonRegistrar2 + "]");
    action.run();
    assertThat(RESPONSE.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .anyMatch(registrarPoc -> "Test Registrar 1".equals(registrarPoc.getName())))
        .isEqualTo(true);
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .anyMatch(registrarPoc -> "Test Registrar 2".equals(registrarPoc.getName())))
        .isEqualTo(true);
  }

  @Test
  void testSuccess_postUpdateContactInfo() {
    testRegistrarPoc = testRegistrarPoc.asBuilder().setEmailAddress("incorrect@email.com").build();
    insertInDb(testRegistrarPoc);
    ContactAction action =
        createAction(
            Action.Method.POST,
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(
                    createUser(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build()))),
            "registrarId",
            "[" + jsonRegistrar1 + "," + jsonRegistrar2 + "]");
    action.run();
    assertThat(RESPONSE.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .anyMatch(
                    registrarPoc -> {
                      if ("Test Registrar 1".equals(registrarPoc.getName())) {
                        return registrarPoc.getEmailAddress().equals("test.registrar1@example.com");
                      }
                      return false;
                    }))
        .isEqualTo(true);
  }

  @Test
  void testSuccess_postDeleteContactInfo() {
    insertInDb(testRegistrarPoc);
    ContactAction action =
        createAction(
            Action.Method.POST,
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(
                    createUser(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build()))),
            "registrarId",
            "[" + jsonRegistrar2 + "]");
    action.run();
    assertThat(RESPONSE.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .anyMatch(registrarPoc -> "Test Registrar 1".equals(registrarPoc.getName())))
        .isEqualTo(false);
  }

  @Test
  void testFailure_postDeleteContactInfo_missingPermission() {
    insertInDb(testRegistrarPoc);
    ContactAction action =
        createAction(
            Action.Method.POST,
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(
                    createUser(
                        new UserRoles.Builder()
                            .setRegistrarRoles(
                                ImmutableMap.of("registrarId", RegistrarRole.ACCOUNT_MANAGER))
                            .build()))),
            "registrarId",
            "[" + jsonRegistrar2 + "]");
    action.run();
    assertThat(RESPONSE.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_FORBIDDEN);
  }

  private User createUser(UserRoles userRoles) {
    return new User.Builder()
        .setEmailAddress("email@email.com")
        .setGaiaId("gaiaId")
        .setUserRoles(userRoles)
        .build();
  }

  private ContactAction createAction(
      Action.Method method, AuthResult authResult, String registrarId, String contacts) {
    if (method.equals(Action.Method.GET)) {
      when(request.getMethod()).thenReturn(Action.Method.GET.toString());
      return new ContactAction(
          request, authResult, RESPONSE, GSON, registrarId, Optional.ofNullable(null));
    } else {
      when(request.getMethod()).thenReturn(Action.Method.POST.toString());
      when(request.getParameter("contacts")).thenReturn(contacts);
      Optional<ImmutableSet<RegistrarPoc>> maybeContacts =
          RegistrarConsoleModule.provideContacts(request, GSON);
      return new ContactAction(request, authResult, RESPONSE, GSON, registrarId, maybeContacts);
    }
  }
}
