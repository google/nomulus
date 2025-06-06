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
import static google.registry.model.registrar.RegistrarPoc.Type.ABUSE;
import static google.registry.model.registrar.RegistrarPoc.Type.ADMIN;
import static google.registry.model.registrar.RegistrarPoc.Type.MARKETING;
import static google.registry.model.registrar.RegistrarPoc.Type.TECH;
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
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.request.Action;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeResponse;
import google.registry.ui.server.console.ConsoleActionBaseTestCase;
import google.registry.util.EmailMessage;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.io.IOException;
import java.util.HashMap;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link google.registry.ui.server.console.settings.ContactAction}. */
class ContactActionTest extends ConsoleActionBaseTestCase {
  private static String jsonRegistrar1 =
      "{\"id\":%s,\"name\":\"Test Registrar 1\","
          + "\"emailAddress\":\"test.registrar1@example.com\","
          + "\"registrarId\":\"registrarId\","
          + "\"phoneNumber\":\"+1.9999999999\",\"faxNumber\":\"+1.9999999991\","
          + "\"types\":[\"ADMIN\"],\"visibleInWhoisAsAdmin\":true,"
          + "\"visibleInWhoisAsTech\":false,\"visibleInDomainWhoisAsAbuse\":false}";

  private Registrar testRegistrar;
  private RegistrarPoc adminPoc;
  private RegistrarPoc techPoc;
  private RegistrarPoc marketingPoc;

  @BeforeEach
  void beforeEach() {
    testRegistrar = saveRegistrar("registrarId");
    adminPoc =
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
    techPoc =
        adminPoc
            .asBuilder()
            .setName("Test Registrar 2")
            .setTypes(ImmutableSet.of(TECH))
            .setVisibleInWhoisAsTech(true)
            .setVisibleInWhoisAsAdmin(false)
            .setEmailAddress("test.registrar2@example.com")
            .setPhoneNumber("+1.1234567890")
            .setFaxNumber("+1.1234567891")
            .build();
    marketingPoc =
        adminPoc
            .asBuilder()
            .setName("Test Registrar 3")
            .setTypes(ImmutableSet.of(MARKETING))
            .setVisibleInWhoisAsAdmin(false)
            .setEmailAddress("test.registrar3@example.com")
            .setPhoneNumber("+1.1238675309")
            .setFaxNumber("+1.1238675309")
            .build();
  }

  @Test
  void testSuccess_getContactInfo() throws IOException {
    insertInDb(adminPoc);
    RegistrarPoc adminPocDb = DatabaseHelper.loadByKey(adminPoc.createVKey());
    ContactAction action =
        createAction(Action.Method.GET, fteUser, testRegistrar.getRegistrarId(), null);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).contains(String.format(jsonRegistrar1, adminPocDb.getId()));
  }

  @Test
  void testSuccess_noOp() throws IOException {
    insertInDb(adminPoc);
    ContactAction action =
        createAction(Action.Method.PUT, fteUser, testRegistrar.getRegistrarId(), adminPoc);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(consoleApiParams.sendEmailUtils().gmailClient, never()).sendEmail(any());
  }

  @Test
  void testSuccess_postCreateContactInfo() throws IOException {
    insertInDb(adminPoc);
    ContactAction action =
        createAction(Action.Method.POST, fteUser, testRegistrar.getRegistrarId(), techPoc);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .map(r -> r.getName())
                .collect(toImmutableList()))
        .containsExactly("Test Registrar 1", "Test Registrar 2");
  }

  @Test
  void testSuccess_postUpdateContactInfo() throws IOException {
    RegistrarPoc techPocIncorrect =
        techPoc.asBuilder().setEmailAddress("incorrect@email.com").build();
    insertInDb(adminPoc, techPocIncorrect);
    techPocIncorrect = DatabaseHelper.loadByKey(techPocIncorrect.createVKey());
    ContactAction action =
        createAction(
            Action.Method.PUT,
            fteUser,
            testRegistrar.getRegistrarId(),
            techPocIncorrect.asBuilder().setEmailAddress(techPoc.getEmailAddress()).build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
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
    insertInDb(adminPoc, techPoc);
    ContactAction action =
        createAction(
            Action.Method.POST,
            fteUser,
            testRegistrar.getRegistrarId(),
            techPoc.asBuilder().setEmailAddress("test.registrar1@example.com").build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload())
        .isEqualTo(
            "One email address (test.registrar1@example.com) cannot be used for multiple contacts");
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .map(r -> r.getName())
                .collect(toImmutableList()))
        .containsExactly("Test Registrar 1", "Test Registrar 2");
  }

  @Test
  void testFailure_postUpdateContactInfo_requiredContactRemoved() throws IOException {
    insertInDb(adminPoc);
    adminPoc = DatabaseHelper.loadByKey(adminPoc.createVKey());
    ContactAction action =
        createAction(
            Action.Method.PUT,
            fteUser,
            testRegistrar.getRegistrarId(),
            adminPoc.asBuilder().setTypes(ImmutableSet.of(ABUSE)).build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("Must have at least one primary contact");
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .collect(toImmutableList()))
        .containsExactly(adminPoc);
  }

  @Test
  void testFailure_postUpdateContactInfo_phoneNumberRemoved() throws IOException {
    adminPoc = adminPoc.asBuilder().setTypes(ImmutableSet.of(ADMIN, TECH)).build();
    insertInDb(adminPoc);
    adminPoc = DatabaseHelper.loadByKey(adminPoc.createVKey());
    ContactAction action =
        createAction(
            Action.Method.PUT,
            fteUser,
            testRegistrar.getRegistrarId(),
            adminPoc
                .asBuilder()
                .setPhoneNumber(null)
                .setTypes(ImmutableSet.of(ADMIN, TECH))
                .build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload())
        .isEqualTo("Please provide a phone number for at least one technical contact");
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .collect(toImmutableList()))
        .containsExactly(adminPoc);
  }

  @Test
  void testFailure_postUpdateContactInfo_whoisContactMissingPhoneNumber() throws IOException {
    ContactAction action =
        createAction(
            Action.Method.POST,
            fteUser,
            testRegistrar.getRegistrarId(),
            adminPoc.asBuilder().setPhoneNumber(null).setVisibleInDomainWhoisAsAbuse(true).build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload())
        .isEqualTo("The abuse contact visible in domain WHOIS query must have a phone number");
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .collect(toImmutableList()))
        .isEmpty();
  }

  @Test
  void testFailure_postUpdateContactInfo_whoisContactPhoneNumberRemoved() throws IOException {
    adminPoc = adminPoc.asBuilder().setVisibleInDomainWhoisAsAbuse(true).build();
    insertInDb(adminPoc);
    adminPoc = DatabaseHelper.loadByKey(adminPoc.createVKey());
    ContactAction action =
        createAction(
            Action.Method.PUT,
            fteUser,
            testRegistrar.getRegistrarId(),
            adminPoc.asBuilder().setVisibleInDomainWhoisAsAbuse(false).build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload())
        .isEqualTo("An abuse contact visible in domain WHOIS query must be designated");
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .collect(toImmutableList()))
        .containsExactly(adminPoc);
  }

  @Test
  void testSuccess_sendsEmail() throws IOException, AddressException {
    insertInDb(techPoc);
    RegistrarPoc techPocDb = DatabaseHelper.loadByKey(techPoc.createVKey());
    Long id = techPocDb.getId();
    ContactAction action =
        createAction(
            Action.Method.PUT,
            fteUser,
            testRegistrar.getRegistrarId(),
            techPocDb.asBuilder().setEmailAddress("incorrect@example.com").build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(consoleApiParams.sendEmailUtils().gmailClient, times(1))
        .sendEmail(
            EmailMessage.newBuilder()
                .setSubject(
                    "Registrar New Registrar (registrarId) updated in registry unittest"
                        + " environment")
                .setBody(
                    "The following changes were made in registry unittest environment to the"
                        + " registrar registrarId by admin fte@email.tld:\n"
                        + "\n"
                        + "contacts:\n"
                        + "    ADDED:\n"
                        + "        {id="
                        + id
                        + ", name=Test Registrar 2,"
                        + " emailAddress=incorrect@example.com, registrarId=registrarId,"
                        + " registryLockEmailAddress=null, phoneNumber=+1.1234567890,"
                        + " faxNumber=+1.1234567891, types=[TECH],"
                        + " visibleInWhoisAsAdmin=false, visibleInWhoisAsTech=true,"
                        + " visibleInDomainWhoisAsAbuse=false,"
                        + " allowedToSetRegistryLockPassword=false}\n"
                        + "    REMOVED:\n"
                        + "        {id="
                        + id
                        + ", name=Test Registrar 2, emailAddress=test.registrar2@example.com,"
                        + " registrarId=registrarId, registryLockEmailAddress=null,"
                        + " phoneNumber=+1.1234567890, faxNumber=+1.1234567891, types=[TECH],"
                        + " visibleInWhoisAsAdmin=false,"
                        + " visibleInWhoisAsTech=true, visibleInDomainWhoisAsAbuse=false,"
                        + " allowedToSetRegistryLockPassword=false}\n"
                        + "    FINAL CONTENTS:\n"
                        + "        {id="
                        + id
                        + ", name=Test Registrar 2,"
                        + " emailAddress=incorrect@example.com, registrarId=registrarId,"
                        + " registryLockEmailAddress=null, phoneNumber=+1.1234567890,"
                        + " faxNumber=+1.1234567891, types=[TECH],"
                        + " visibleInWhoisAsAdmin=false, visibleInWhoisAsTech=true,"
                        + " visibleInDomainWhoisAsAbuse=false,"
                        + " allowedToSetRegistryLockPassword=false}\n")
                .setRecipients(ImmutableList.of(new InternetAddress("notification@test.example")))
                .build());
  }

  @Test
  void testSuccess_postDeleteContactInfo() throws IOException {
    insertInDb(adminPoc, techPoc, marketingPoc);
    ContactAction action =
        createAction(Action.Method.DELETE, fteUser, testRegistrar.getRegistrarId(), marketingPoc);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .map(r -> r.getName())
                .collect(toImmutableList()))
        .containsExactly("Test Registrar 1", "Test Registrar 2");
  }

  @Test
  void testFailure_postDeleteContactInfo_missingPermission() throws IOException {
    insertInDb(adminPoc);
    ContactAction action =
        createAction(
            Action.Method.DELETE,
            new User.Builder()
                .setEmailAddress("email@email.com")
                .setUserRoles(
                    new UserRoles.Builder()
                        .setRegistrarRoles(
                            ImmutableMap.of(
                                testRegistrar.getRegistrarId(), RegistrarRole.ACCOUNT_MANAGER))
                        .build())
                .build(),
            testRegistrar.getRegistrarId(),
            techPoc);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  @Test
  void testFailure_changesAdminEmail() throws Exception {
    insertInDb(adminPoc);
    RegistrarPoc adminPocDb = DatabaseHelper.loadByKey(adminPoc.createVKey());
    ContactAction action =
        createAction(
            Action.Method.PUT,
            fteUser,
            testRegistrar.getRegistrarId(),
            adminPocDb.asBuilder().setEmailAddress("testemail@example.com").build());
    action.run();
    FakeResponse fakeResponse = response;
    assertThat(fakeResponse.getStatus()).isEqualTo(400);
    assertThat(fakeResponse.getPayload())
        .isEqualTo("Cannot remove or change the email address of primary contacts");
  }

  private ContactAction createAction(
      Action.Method method, User user, String registrarId, RegistrarPoc contact)
      throws IOException {
    consoleApiParams = ConsoleApiParamsUtils.createFake(AuthResult.createUser(user));
    when(consoleApiParams.request().getMethod()).thenReturn(method.toString());
    response = (FakeResponse) consoleApiParams.response();
    if (method.equals(Action.Method.GET)) {
      return new ContactAction(consoleApiParams, registrarId, Optional.empty());
    }
    return new ContactAction(consoleApiParams, registrarId, Optional.of(contact));
  }
}
