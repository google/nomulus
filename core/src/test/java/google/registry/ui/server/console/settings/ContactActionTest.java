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
import static google.registry.model.registrar.RegistrarPoc.Type.ABUSE;
import static google.registry.model.registrar.RegistrarPoc.Type.ADMIN;
import static google.registry.model.registrar.RegistrarPoc.Type.MARKETING;
import static google.registry.model.registrar.RegistrarPoc.Type.TECH;
import static google.registry.testing.DatabaseHelper.deleteResource;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistResources;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static google.registry.ui.server.console.settings.ContactAction.checkContactRequirements;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import google.registry.request.RequestModule;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.FakeResponse;
import google.registry.ui.server.console.ConsoleActionBaseTestCase;
import google.registry.ui.server.console.ConsoleModule;
import google.registry.ui.server.console.settings.ContactAction.ContactRequirementException;
import google.registry.util.EmailMessage;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.io.IOException;
import java.util.HashMap;
import javax.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link google.registry.ui.server.console.settings.ContactAction}. */
class ContactActionTest extends ConsoleActionBaseTestCase {
  private static String jsonRegistrar1 =
      "{\"id\":%s,\"name\":\"Test Registrar 1\","
          + "\"emailAddress\":\"test.registrar1@example.com\","
          + "\"registrarId\":\"registrarId\","
          + "\"phoneNumber\":\"+1.9999999999\",\"faxNumber\":\"+1.9999999991\","
          + "\"types\":[\"ADMIN\"],\"visibleInRdapAsAdmin\":true,"
          + "\"visibleInRdapAsTech\":false,\"visibleInDomainRdapAsAbuse\":false}";

  private Registrar testRegistrar;
  private RegistrarPoc adminPoc;
  private RegistrarPoc techPoc;
  private RegistrarPoc marketingPoc;

  @BeforeEach
  void beforeEach() {
    testRegistrar = saveRegistrar("registrarId");
    adminPoc =
        persistResource(
            new RegistrarPoc.Builder()
                .setRegistrar(testRegistrar)
                .setName("Test Registrar 1")
                .setEmailAddress("test.registrar1@example.com")
                .setPhoneNumber("+1.9999999999")
                .setFaxNumber("+1.9999999991")
                .setTypes(ImmutableSet.of(ADMIN))
                .setVisibleInRdapAsAdmin(true)
                .setVisibleInRdapAsTech(false)
                .setVisibleInDomainRdapAsAbuse(false)
                .build());
    techPoc =
        adminPoc
            .asBuilder()
            .setName("Test Registrar 2")
            .setTypes(ImmutableSet.of(TECH))
            .setVisibleInRdapAsTech(true)
            .setVisibleInRdapAsAdmin(false)
            .setEmailAddress("test.registrar2@example.com")
            .setPhoneNumber("+1.1234567890")
            .setFaxNumber("+1.1234567891")
            .build();
    marketingPoc =
        adminPoc
            .asBuilder()
            .setName("Test Registrar 3")
            .setTypes(ImmutableSet.of(MARKETING))
            .setVisibleInRdapAsAdmin(false)
            .setEmailAddress("test.registrar3@example.com")
            .setPhoneNumber("+1.1238675309")
            .setFaxNumber("+1.1238675309")
            .build();
  }

  @Test
  void testSuccess_getContactInfo() throws IOException {
    ContactAction action =
        createAction(Action.Method.GET, fteUser, testRegistrar.getRegistrarId(), null);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).contains(String.format(jsonRegistrar1, adminPoc.getId()));
  }

  @Test
  void testSuccess_noOp() throws IOException {
    ContactAction action =
        createAction(Action.Method.PUT, fteUser, testRegistrar.getRegistrarId(), adminPoc);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(consoleApiParams.sendEmailUtils().gmailClient, never()).sendEmail(any());
  }

  @Test
  void testSuccess_postCreateContactInfo() throws IOException {
    ContactAction action =
        createAction(Action.Method.POST, fteUser, testRegistrar.getRegistrarId(), techPoc);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .map(r -> r.getName()))
        .containsExactly("Test Registrar 1", "Test Registrar 2");
  }

  @Test
  void testSuccess_postUpdateContactInfo() throws IOException {
    RegistrarPoc techPocIncorrect =
        persistResource(techPoc.asBuilder().setEmailAddress("incorrect@email.com").build());
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
    persistResource(techPoc);
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
                .map(r -> r.getName()))
        .containsExactly("Test Registrar 1", "Test Registrar 2");
  }

  @Test
  void testFailure_postUpdateContactInfo_requiredContactRemoved() throws IOException {
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
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId())))
        .containsExactly(adminPoc);
  }

  @Test
  void testFailure_postUpdateContactInfo_phoneNumberRemoved() throws IOException {
    adminPoc = persistResource(adminPoc.asBuilder().setTypes(ImmutableSet.of(ADMIN, TECH)).build());
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
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId())))
        .containsExactly(adminPoc);
  }

  @Test
  void testFailure_postUpdateContactInfo_rdapContactMissingPhoneNumber() throws IOException {
    ContactAction action =
        createAction(
            Action.Method.POST,
            fteUser,
            testRegistrar.getRegistrarId(),
            techPoc.asBuilder().setPhoneNumber(null).setVisibleInDomainRdapAsAbuse(true).build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload())
        .isEqualTo("The abuse contact visible in domain RDAP query must have a phone number");
  }

  @Test
  void testFailure_postUpdateContactInfo_rdapContactPhoneNumberRemoved() throws IOException {
    adminPoc = persistResource(adminPoc.asBuilder().setVisibleInDomainRdapAsAbuse(true).build());
    ContactAction action =
        createAction(
            Action.Method.PUT,
            fteUser,
            testRegistrar.getRegistrarId(),
            adminPoc.asBuilder().setVisibleInDomainRdapAsAbuse(false).build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload())
        .isEqualTo("An abuse contact visible in domain RDAP query must be designated");
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId())))
        .containsExactly(adminPoc);
  }

  @Test
  void testSuccess_sendsEmail() throws IOException, AddressException {
    deleteResource(adminPoc);
    techPoc = persistResource(techPoc);
    Long id = techPoc.getId();
    ContactAction action =
        createAction(
            Action.Method.PUT,
            fteUser,
            testRegistrar.getRegistrarId(),
            techPoc.asBuilder().setEmailAddress("incorrect@example.com").build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(consoleApiParams.sendEmailUtils().gmailClient, times(1))
        .sendEmail(
            EmailMessage.newBuilder()
                .setSubject(
                    "Registrar New Registrar (registrarId) updated in registry unittest"
                        + " environment")
                .setBody(
"""
The following changes were made in registry unittest environment to the registrar registrarId \
by admin fte@email.tld:

contacts:
    ADDED:
        {id=5, name=Test Registrar 2, emailAddress=incorrect@example.com, registrarId=registrarId, \
phoneNumber=+1.1234567890, faxNumber=+1.1234567891, types=[TECH], visibleInRdapAsAdmin=false, \
visibleInRdapAsTech=true, visibleInDomainRdapAsAbuse=false}
    REMOVED:
        {id=5, name=Test Registrar 2, emailAddress=test.registrar2@example.com, \
registrarId=registrarId, phoneNumber=+1.1234567890, faxNumber=+1.1234567891, types=[TECH], \
visibleInRdapAsAdmin=false, visibleInRdapAsTech=true, visibleInDomainRdapAsAbuse=false}
    FINAL CONTENTS:
        {id=5, name=Test Registrar 2, emailAddress=incorrect@example.com, registrarId=registrarId, \
phoneNumber=+1.1234567890, faxNumber=+1.1234567891, types=[TECH], visibleInRdapAsAdmin=false, \
visibleInRdapAsTech=true, visibleInDomainRdapAsAbuse=false}
""")
                .setRecipients(ImmutableList.of(new InternetAddress("notification@test.example")))
                .build());
  }

  @Test
  void testSuccess_postDeleteContactInfo() throws IOException {
    persistResources(techPoc, marketingPoc);
    ContactAction action =
        createAction(Action.Method.DELETE, fteUser, testRegistrar.getRegistrarId(), marketingPoc);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .map(r -> r.getName()))
        .containsExactly("Test Registrar 1", "Test Registrar 2");
  }

  @Test
  void testFailure_postDeleteContactInfo_missingPermission() throws IOException {
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
    ContactAction action =
        createAction(
            Action.Method.PUT,
            fteUser,
            testRegistrar.getRegistrarId(),
            adminPoc.asBuilder().setEmailAddress("testemail@example.com").build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("Cannot alter the set of primary contacts");
  }

  @Test
  void testFailure_addsAdminEmail() throws Exception {
    RegistrarPoc newAdminPoc =
        adminPoc
            .asBuilder()
            .setName("New Admin Contact")
            .setEmailAddress("new.admin@example.com")
            .build();
    ContactAction action =
        createAction(Action.Method.POST, fteUser, testRegistrar.getRegistrarId(), newAdminPoc);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("Cannot alter the set of primary contacts");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "contact@example.invalidtld",
        "USER@EXAMPLE.INVALIDTLD",
        "user@example.invalid",
        "user@example.test",
        "user@example.local",
        "user@localhost",
        "user@com",
        "user@co.uk",
        "",
        "   ",
        "notanemail",
        "@example.com",
        "user@",
        "a@b@example.com",
        "\"a@b\"@example.com",
        "user@.example.com",
        "user@example.com.",
        ".user@example.com",
        "user.@example.com",
        "user@example..com",
        "user@sub_domain.com",
        "Display Name <user@example.com>",
        "user@münchen.invalidtld",
        "user@xn---.com"
      })
  void testFailure_postInvalidEmailAddress_returnsBadRequest(String invalidEmail)
      throws IOException {
    String payload =
        GSON.toJson(
            ImmutableMap.of(
                "name",
                "Invalid Contact",
                "emailAddress",
                invalidEmail,
                "registrarId",
                testRegistrar.getRegistrarId(),
                "phoneNumber",
                "+1.1234567890",
                "types",
                ImmutableList.of("MARKETING")));
    ContactAction action =
        createActionWithJsonBody(
            Action.Method.POST, fteUser, testRegistrar.getRegistrarId(), payload);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId())))
        .containsExactly(adminPoc);
  }

  @Test
  void testFailure_putInvalidPublicSuffixEmail_returnsBadRequest() throws IOException {
    RegistrarPoc savedMarketing = persistResource(marketingPoc);
    String payload =
        GSON.toJson(
            ImmutableMap.of(
                "id",
                savedMarketing.getId(),
                "name",
                "Marketing Contact",
                "emailAddress",
                "contact@example.invalidtld",
                "registrarId",
                testRegistrar.getRegistrarId(),
                "phoneNumber",
                "+1.1238675309",
                "types",
                ImmutableList.of("MARKETING")));
    ContactAction action =
        createActionWithJsonBody(
            Action.Method.PUT, fteUser, testRegistrar.getRegistrarId(), payload);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .map(RegistrarPoc::getEmailAddress))
        .containsExactly("test.registrar1@example.com", "test.registrar3@example.com");
  }

  @Test
  void testFailure_nullEmailAddressInJson_returnsBadRequest() throws IOException {
    String payload =
        "{\"name\":\"Null Email\",\"registrarId\":\"registrarId\",\"types\":[\"MARKETING\"]}";
    ContactAction action =
        createActionWithJsonBody(
            Action.Method.POST, fteUser, testRegistrar.getRegistrarId(), payload);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
  }

  @Test
  void testFailure_emptyPayload_returnsBadRequest() throws IOException {
    ContactAction action =
        createActionWithJsonBody(Action.Method.POST, fteUser, testRegistrar.getRegistrarId(), "");
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("Contact parameter is not present");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "USER@EXAMPLE.COM",
        "contact@company.co.uk",
        "user@xn--mnchen-3ya.de",
        "user@domain.xn--q9jyb4c",
        "user@münchen.de",
        "user@domain.みんな"
      })
  void testSuccess_postValidPublicSuffixAndIdnEmails(String validEmail) throws IOException {
    String payload =
        GSON.toJson(
            ImmutableMap.of(
                "name",
                "Valid Contact",
                "emailAddress",
                validEmail,
                "registrarId",
                testRegistrar.getRegistrarId(),
                "phoneNumber",
                "+1.1238675309",
                "types",
                ImmutableList.of("MARKETING")));
    ContactAction action =
        createActionWithJsonBody(
            Action.Method.POST, fteUser, testRegistrar.getRegistrarId(), payload);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .map(RegistrarPoc::getEmailAddress))
        .contains(validEmail);
  }

  @Test
  void testCheckContactRequirements_handlesNullAndMalformedEmailsDirectly() {
    RegistrarPoc nullEmailPoc = new RegistrarPoc();
    nullEmailPoc.setRegistrarId(testRegistrar.getRegistrarId());
    nullEmailPoc.setEmailAddress(null);
    assertThrows(
        ContactRequirementException.class,
        () ->
            checkContactRequirements(
                ImmutableSet.of(adminPoc), ImmutableSet.of(adminPoc, nullEmailPoc)));

    RegistrarPoc malformedEmailPoc = new RegistrarPoc();
    malformedEmailPoc.setRegistrarId(testRegistrar.getRegistrarId());
    malformedEmailPoc.setEmailAddress("user@.bad..domain");
    assertThrows(
        ContactRequirementException.class,
        () ->
            checkContactRequirements(
                ImmutableSet.of(adminPoc), ImmutableSet.of(adminPoc, malformedEmailPoc)));
  }

  @Test
  void testSuccess_postNewValidContactWhileLegacyInvalidContactExists() throws IOException {
    persistResource(marketingPoc.asBuilder().setEmailAddress("contact@example.invalidtld").build());
    ContactAction action =
        createAction(Action.Method.POST, fteUser, testRegistrar.getRegistrarId(), techPoc);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .map(RegistrarPoc::getEmailAddress))
        .containsExactly(
            "test.registrar1@example.com",
            "contact@example.invalidtld",
            "test.registrar2@example.com");
  }

  @Test
  void testFailure_putLegacyInvalidContactWithoutFixingEmail() throws IOException {
    RegistrarPoc legacyInvalidPoc =
        persistResource(
            marketingPoc.asBuilder().setEmailAddress("contact@example.invalidtld").build());
    ContactAction action =
        createAction(
            Action.Method.PUT,
            fteUser,
            testRegistrar.getRegistrarId(),
            legacyInvalidPoc.asBuilder().setPhoneNumber("+1.5555555555").build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
  }

  @Test
  void testSuccess_deleteLegacyInvalidPublicSuffixContact() throws IOException {
    RegistrarPoc legacyInvalidPoc =
        persistResource(
            marketingPoc.asBuilder().setEmailAddress("contact@example.invalidtld").build());
    ContactAction action =
        createAction(
            Action.Method.DELETE, fteUser, testRegistrar.getRegistrarId(), legacyInvalidPoc);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .map(RegistrarPoc::getEmailAddress))
        .containsExactly("test.registrar1@example.com");
  }

  @Test
  void testSuccess_putFixLegacyInvalidPublicSuffixContact() throws IOException {
    RegistrarPoc legacyInvalidPoc =
        persistResource(
            marketingPoc.asBuilder().setEmailAddress("contact@example.invalidtld").build());
    ContactAction action =
        createAction(
            Action.Method.PUT,
            fteUser,
            testRegistrar.getRegistrarId(),
            legacyInvalidPoc.asBuilder().setEmailAddress("marketing@example.com").build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .filter(r -> r.registrarId.equals(testRegistrar.getRegistrarId()))
                .map(RegistrarPoc::getEmailAddress))
        .containsExactly("test.registrar1@example.com", "marketing@example.com");
  }

  private ContactAction createActionWithJsonBody(
      Action.Method method, User user, String registrarId, @Nullable String jsonBody)
      throws IOException {
    consoleApiParams = ConsoleApiParamsUtils.createFake(AuthResult.createUser(user));
    when(consoleApiParams.request().getMethod()).thenReturn(method.toString());
    response = (FakeResponse) consoleApiParams.response();
    when(consoleApiParams.request().getInputStream())
        .thenReturn(createServletInputStream(jsonBody == null ? "" : jsonBody));
    byte[] payloadBytes = RequestModule.providePayloadAsBytes(consoleApiParams.request());
    String payloadString =
        RequestModule.providePayloadAsString(payloadBytes, consoleApiParams.request());
    return new ContactAction(
        consoleApiParams,
        registrarId,
        ConsoleModule.provideContacts(GSON, RequestModule.provideJsonBody(payloadString, GSON)));
  }

  private ContactAction createAction(
      Action.Method method, User user, String registrarId, @Nullable RegistrarPoc contact)
      throws IOException {
    return createActionWithJsonBody(
        method,
        user,
        registrarId,
        method.equals(Action.Method.GET) || contact == null ? "" : GSON.toJson(contact));
  }
}
