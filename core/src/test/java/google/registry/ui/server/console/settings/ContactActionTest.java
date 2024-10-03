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
import static google.registry.model.registrar.RegistrarPocBase.Type.ABUSE;
import static google.registry.model.registrar.RegistrarPocBase.Type.ADMIN;
import static google.registry.model.registrar.RegistrarPocBase.Type.TECH;
import static google.registry.testing.DatabaseHelper.createAdminUser;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
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
import google.registry.ui.server.console.ConsoleApiParams;
import google.registry.util.EmailMessage;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.io.IOException;
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
          + "\"types\":[\"ADMIN\"],\"visibleInWhoisAsAdmin\":true,"
          + "\"visibleInWhoisAsTech\":false,\"visibleInDomainWhoisAsAbuse\":false}";

  private Registrar testRegistrar;
  private ConsoleApiParams consoleApiParams;
  private RegistrarPoc testRegistrarPoc1;
  private RegistrarPoc testRegistrarPoc2;

  private static final Gson GSON = RequestModule.provideGson();

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    testRegistrar = saveRegistrar("registrarId");
    testRegistrarPoc1 =
        new RegistrarPoc.Builder()
            .setRegistrar(testRegistrar)
            .setName("Test Registrar 1")
            .setEmailAddress("test.registrar1@example.com")
            .setPhoneNumber("+1.9999999999")
            .setFaxNumber("+1.9999999991")
            .setTypes(ImmutableSet.of(ADMIN))
            .setVisibleInWhoisAsAdmin(true)
            .setVisibleInWhoisAsTech(false)
            .setVisibleInDomainWhoisAsAbuse(false)
            .build();
    testRegistrarPoc2 =
        testRegistrarPoc1
            .asBuilder()
            .setName("Test Registrar 2")
            .setEmailAddress("test.registrar2@example.com")
            .setPhoneNumber("+1.1234567890")
            .setFaxNumber("+1.1234567891")
            .build();
  }

  @Test
  void testSuccess_getContactInfo() throws IOException {
    insertInDb(testRegistrarPoc1);
    ContactAction action =
        createAction(
            Action.Method.GET,
            AuthResult.createUser(createAdminUser("email@email.com")),
            testRegistrar.getRegistrarId());
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_OK);
    assertThat(((FakeResponse) consoleApiParams.response()).getPayload())
        .isEqualTo("[" + jsonRegistrar1 + "]");
  }

  @Test
  void testSuccess_noOp() throws IOException {
    insertInDb(testRegistrarPoc1);
    ContactAction action =
        createAction(
            Action.Method.POST,
            AuthResult.createUser(createAdminUser("email@email.com")),
            testRegistrar.getRegistrarId(),
            testRegistrarPoc1);
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_OK);
    verify(consoleApiParams.sendEmailUtils().gmailClient, never()).sendEmail(any());
  }

  @Test
  void testSuccess_onlyContactsWithNonEmptyType() throws IOException {
    testRegistrarPoc1 = testRegistrarPoc1.asBuilder().setTypes(ImmutableSet.of()).build();
    insertInDb(testRegistrarPoc1);
    ContactAction action =
        createAction(
            Action.Method.GET,
            AuthResult.createUser(createAdminUser("email@email.com")),
            testRegistrar.getRegistrarId());
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_OK);
    assertThat(((FakeResponse) consoleApiParams.response()).getPayload()).isEqualTo("[]");
  }

  @Test
  void testSuccess_postCreateContactInfo() throws IOException {
    insertInDb(testRegistrarPoc1);
    ContactAction action =
        createAction(
            Action.Method.POST,
            AuthResult.createUser(createAdminUser("email@email.com")),
            testRegistrar.getRegistrarId(),
            testRegistrarPoc1,
            testRegistrarPoc2);
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
    insertInDb(testRegistrarPoc1.asBuilder().setEmailAddress("incorrect@email.com").build());
    ContactAction action =
        createAction(
            Action.Method.POST,
            AuthResult.createUser(createAdminUser("email@email.com")),
            testRegistrar.getRegistrarId(),
            testRegistrarPoc1,
            testRegistrarPoc2);
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
  void testFailure_postUpdateContactInfo_duplicateEmails() throws IOException {
    ContactAction action =
        createAction(
            Action.Method.POST,
            AuthResult.createUser(createAdminUser("email@email.com")),
            testRegistrar.getRegistrarId(),
            testRegistrarPoc1,
            testRegistrarPoc2.asBuilder().setEmailAddress("test.registrar1@example.com").build());
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(((FakeResponse) consoleApiParams.response()).getPayload())
        .isEqualTo(
            "One email address (test.registrar1@example.com) cannot be used for multiple contacts");
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .collect(toImmutableList()))
        .isEmpty();
  }

  @Test
  void testFailure_postUpdateContactInfo_requiredContactRemoved() throws IOException {
    insertInDb(testRegistrarPoc1);
    ContactAction action =
        createAction(
            Action.Method.POST,
            AuthResult.createUser(createAdminUser("email@email.com")),
            testRegistrar.getRegistrarId(),
            testRegistrarPoc1.asBuilder().setTypes(ImmutableSet.of(ABUSE)).build());
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(((FakeResponse) consoleApiParams.response()).getPayload())
        .isEqualTo("Must have at least one primary contact");
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .collect(toImmutableList()))
        .containsExactly(testRegistrarPoc1);
  }

  @Test
  void testFailure_postUpdateContactInfo_phoneNumberRemoved() throws IOException {
    testRegistrarPoc1 =
        testRegistrarPoc1.asBuilder().setTypes(ImmutableSet.of(ADMIN, TECH)).build();
    insertInDb(testRegistrarPoc1);
    ContactAction action =
        createAction(
            Action.Method.POST,
            AuthResult.createUser(createAdminUser("email@email.com")),
            testRegistrar.getRegistrarId(),
            testRegistrarPoc1
                .asBuilder()
                .setPhoneNumber(null)
                .setTypes(ImmutableSet.of(ADMIN, TECH))
                .build());
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(((FakeResponse) consoleApiParams.response()).getPayload())
        .isEqualTo("Please provide a phone number for at least one technical contact");
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .collect(toImmutableList()))
        .containsExactly(testRegistrarPoc1);
  }

  @Test
  void testFailure_postUpdateContactInfo_whoisContactMissingPhoneNumber() throws IOException {
    ContactAction action =
        createAction(
            Action.Method.POST,
            AuthResult.createUser(createAdminUser("email@email.com")),
            testRegistrar.getRegistrarId(),
            testRegistrarPoc1
                .asBuilder()
                .setPhoneNumber(null)
                .setVisibleInDomainWhoisAsAbuse(true)
                .build());
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(((FakeResponse) consoleApiParams.response()).getPayload())
        .isEqualTo("The abuse contact visible in domain WHOIS query must have a phone number");
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .collect(toImmutableList()))
        .isEmpty();
  }

  @Test
  void testFailure_postUpdateContactInfo_whoisContactPhoneNumberRemoved() throws IOException {
    testRegistrarPoc1 = testRegistrarPoc1.asBuilder().setVisibleInDomainWhoisAsAbuse(true).build();
    insertInDb(testRegistrarPoc1);
    ContactAction action =
        createAction(
            Action.Method.POST,
            AuthResult.createUser(createAdminUser("email@email.com")),
            testRegistrar.getRegistrarId(),
            testRegistrarPoc1.asBuilder().setVisibleInDomainWhoisAsAbuse(false).build());
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(((FakeResponse) consoleApiParams.response()).getPayload())
        .isEqualTo("An abuse contact visible in domain WHOIS query must be designated");
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .collect(toImmutableList()))
        .containsExactly(testRegistrarPoc1);
  }

  @Test
  void testFailure_postUpdateContactInfo_newContactCannotSetRegistryLockPassword()
      throws IOException {
    ContactAction action =
        createAction(
            Action.Method.POST,
            AuthResult.createUser(createAdminUser("email@email.com")),
            testRegistrar.getRegistrarId(),
            testRegistrarPoc1
                .asBuilder()
                .setAllowedToSetRegistryLockPassword(true)
                .setRegistryLockEmailAddress("lock@example.com")
                .build());
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(((FakeResponse) consoleApiParams.response()).getPayload())
        .isEqualTo("Cannot set registry lock password directly on new contact");
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .collect(toImmutableList()))
        .isEmpty();
  }

  @Test
  void testFailure_postUpdateContactInfo_cannotModifyRegistryLockEmail() throws IOException {
    testRegistrarPoc1 =
        testRegistrarPoc1
            .asBuilder()
            .setRegistryLockEmailAddress("lock@example.com")
            .setAllowedToSetRegistryLockPassword(true)
            .build();
    insertInDb(testRegistrarPoc1);
    ContactAction action =
        createAction(
            Action.Method.POST,
            AuthResult.createUser(createAdminUser("email@email.com")),
            testRegistrar.getRegistrarId(),
            testRegistrarPoc1
                .asBuilder()
                .setRegistryLockEmailAddress("unlock@example.com")
                .build());
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(((FakeResponse) consoleApiParams.response()).getPayload())
        .isEqualTo("Cannot modify registryLockEmailAddress through the UI");
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .collect(toImmutableList()))
        .containsExactly(testRegistrarPoc1);
  }

  @Test
  void testFailure_postUpdateContactInfo_cannotSetIsAllowedToSetRegistryLockPassword()
      throws IOException {
    testRegistrarPoc1 =
        testRegistrarPoc1
            .asBuilder()
            .setRegistryLockEmailAddress("lock@example.com")
            .setAllowedToSetRegistryLockPassword(false)
            .build();
    insertInDb(testRegistrarPoc1);
    ContactAction action =
        createAction(
            Action.Method.POST,
            AuthResult.createUser(createAdminUser("email@email.com")),
            testRegistrar.getRegistrarId(),
            testRegistrarPoc1.asBuilder().setAllowedToSetRegistryLockPassword(true).build());
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(((FakeResponse) consoleApiParams.response()).getPayload())
        .isEqualTo("Cannot modify isAllowedToSetRegistryLockPassword through the UI");
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .collect(toImmutableList()))
        .containsExactly(testRegistrarPoc1);
  }

  @Test
  void testSuccess_sendsEmail() throws IOException, AddressException {
    insertInDb(testRegistrarPoc1.asBuilder().setEmailAddress("incorrect@email.com").build());
    ContactAction action =
        createAction(
            Action.Method.POST,
            AuthResult.createUser(createAdminUser("email@email.com")),
            testRegistrar.getRegistrarId(),
            testRegistrarPoc1);
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_OK);
    verify(consoleApiParams.sendEmailUtils().gmailClient, times(1))
        .sendEmail(
            EmailMessage.newBuilder()
                .setSubject(
                    "Registrar New Registrar (registrarId) updated in registry unittest"
                        + " environment")
                .setBody(
                    "The following changes were made in registry unittest environment to the"
                        + " registrar registrarId by admin email@email.com:\n"
                        + "\n"
                        + "contacts:\n"
                        + "    ADDED:\n"
                        + "        {name=Test Registrar 1,"
                        + " emailAddress=test.registrar1@example.com, registrarId=registrarId,"
                        + " registryLockEmailAddress=null, phoneNumber=+1.9999999999,"
                        + " faxNumber=+1.9999999991, types=[ADMIN],"
                        + " visibleInWhoisAsAdmin=true, visibleInWhoisAsTech=false,"
                        + " visibleInDomainWhoisAsAbuse=false,"
                        + " allowedToSetRegistryLockPassword=false}\n"
                        + "    REMOVED:\n"
                        + "        {name=Test Registrar 1, emailAddress=incorrect@email.com,"
                        + " registrarId=registrarId, registryLockEmailAddress=null,"
                        + " phoneNumber=+1.9999999999, faxNumber=+1.9999999991, types=[ADMIN],"
                        + " visibleInWhoisAsAdmin=true,"
                        + " visibleInWhoisAsTech=false, visibleInDomainWhoisAsAbuse=false,"
                        + " allowedToSetRegistryLockPassword=false}\n"
                        + "    FINAL CONTENTS:\n"
                        + "        {name=Test Registrar 1,"
                        + " emailAddress=test.registrar1@example.com, registrarId=registrarId,"
                        + " registryLockEmailAddress=null, phoneNumber=+1.9999999999,"
                        + " faxNumber=+1.9999999991, types=[ADMIN],"
                        + " visibleInWhoisAsAdmin=true, visibleInWhoisAsTech=false,"
                        + " visibleInDomainWhoisAsAbuse=false,"
                        + " allowedToSetRegistryLockPassword=false}\n")
                .setRecipients(
                    ImmutableList.of(
                        new InternetAddress("notification@test.example"),
                        new InternetAddress("incorrect@email.com")))
                .build());
  }

  @Test
  void testSuccess_postDeleteContactInfo() throws IOException {
    insertInDb(testRegistrarPoc1);
    ContactAction action =
        createAction(
            Action.Method.POST,
            AuthResult.createUser(createAdminUser("email@email.com")),
            testRegistrar.getRegistrarId(),
            testRegistrarPoc2);
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
    insertInDb(testRegistrarPoc1);
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
            testRegistrarPoc2);
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  private ContactAction createAction(
      Action.Method method, AuthResult authResult, String registrarId, RegistrarPoc... contacts)
      throws IOException {
    consoleApiParams = ConsoleApiParamsUtils.createFake(authResult);
    when(consoleApiParams.request().getMethod()).thenReturn(method.toString());
    if (method.equals(Action.Method.GET)) {
      return new ContactAction(consoleApiParams, registrarId, Optional.empty());
    } else {
      return new ContactAction(
          consoleApiParams, registrarId, Optional.of(ImmutableSet.copyOf(contacts)));
    }
  }
}
