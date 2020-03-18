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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DatastoreHelper.persistSimpleResource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.registrar.RegistrarContact.Type;
import google.registry.testing.AppEngineRule;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for contact_settings.js use of {@link RegistrarSettingsAction}.
 *
 * <p>The default read and session validation tests are handled by the
 * superclass.
 */
@RunWith(JUnit4.class)
public class ContactSettingsTest extends RegistrarSettingsActionTestCase {

  @Test
  public void testPost_readContacts_success() {
    Map<String, Object> response = action.handleJsonRequest(ImmutableMap.of(
        "op", "read",
        "id", CLIENT_ID,
        "args", ImmutableMap.of()));
    @SuppressWarnings("unchecked")
    List<Map<String, ?>> results = (List<Map<String, ?>>) response.get("results");
    assertThat(results.get(0).get("contacts"))
        .isEqualTo(loadRegistrar(CLIENT_ID).toJsonMap().get("contacts"));
    assertMetric(CLIENT_ID, "read", "[OWNER]", "SUCCESS");
  }

  @Test
  public void testPost_loadSaveRegistrar_success() {
    Map<String, Object> response = action.handleJsonRequest(ImmutableMap.of(
        "op", "update",
        "id", CLIENT_ID,
        "args", loadRegistrar(CLIENT_ID).toJsonMap()));
    assertThat(response).containsEntry("status", "SUCCESS");
    assertMetric(CLIENT_ID, "update", "[OWNER]", "SUCCESS");
  }

  @Test
  public void testPost_updateContacts_success() throws Exception {
    // Remove all the contacts but the first by updating with list of
    // just it.
    ImmutableMap<String, String> adminContact1 =
        ImmutableMap.of(
            "name", "contact1",
            "emailAddress", "contact1@email.com",
            "phoneNumber", "+1.2125650001",
            // Have to keep ADMIN or else expect FormException for at-least-one.
            "types", "ADMIN,TECH");

    Registrar registrar = loadRegistrar(CLIENT_ID);
    Map<String, Object> regMap = registrar.toJsonMap();
    regMap.put("contacts", ImmutableList.of(adminContact1));
    Map<String, Object> response =
        action.handleJsonRequest(ImmutableMap.of("op", "update", "id", CLIENT_ID, "args", regMap));
    assertThat(response).containsEntry("status", "SUCCESS");

    RegistrarContact newContact =
        new RegistrarContact.Builder()
            .setParent(registrar)
            .setName(adminContact1.get("name"))
            .setEmailAddress(adminContact1.get("emailAddress"))
            .setPhoneNumber(adminContact1.get("phoneNumber"))
            .setTypes(ImmutableList.of(Type.ADMIN, Type.TECH))
            .build();
    assertThat(loadRegistrar(CLIENT_ID).getContacts()).containsExactly(newContact);
    assertMetric(CLIENT_ID, "update", "[OWNER]", "SUCCESS");
    verifyNotificationEmailsSent();
  }

  @Test
  public void testPost_updateContacts_requiredTypes_error() {
    Map<String, Object> reqJson = loadRegistrar(CLIENT_ID).toJsonMap();
    reqJson.put("contacts", ImmutableList.of(techContact.toJsonMap()));
    Map<String, Object> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "op", "update",
                "id", CLIENT_ID,
                "args", reqJson));
    assertThat(response).containsEntry("status", "ERROR");
    assertThat(response).containsEntry("message", "Must have at least one primary contact");
    assertMetric(CLIENT_ID, "update", "[OWNER]", "ERROR: ContactRequirementException");
  }

  @Test
  public void testPost_updateContacts_requireTechPhone_error() {
    Map<String, Object> reqJson = loadRegistrar(CLIENT_ID).toJsonMap();
    reqJson.put(
        "contacts",
        ImmutableList.of(
            AppEngineRule.makeRegistrarContact2().toJsonMap(),
            techContact.asBuilder().setPhoneNumber(null).build().toJsonMap()));
    Map<String, Object> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "op", "update",
                "id", CLIENT_ID,
                "args", reqJson));
    assertThat(response).containsEntry("status", "ERROR");
    assertThat(response)
        .containsEntry(
            "message", "Please provide a phone number for at least one technical contact");
    assertMetric(CLIENT_ID, "update", "[OWNER]", "ERROR: ContactRequirementException");
  }

  @Test
  public void testPost_updateContacts_cannotRemoveWhoisAbuseContact_error() {
    // First make the contact's info visible in whois as abuse contact info.
    Registrar registrar = loadRegistrar(CLIENT_ID);
    RegistrarContact rc =
        AppEngineRule.makeRegistrarContact2()
            .asBuilder()
            .setVisibleInDomainWhoisAsAbuse(true)
            .build();
    // Lest we anger the timestamp inversion bug.
    // (we also update the registrar so we get the timestamp right)
    registrar = persistResource(registrar);
    persistSimpleResource(rc);

    // Now try to remove the contact.
    rc = rc.asBuilder().setVisibleInDomainWhoisAsAbuse(false).build();
    Map<String, Object> reqJson = registrar.toJsonMap();
    reqJson.put("contacts", ImmutableList.of(rc.toJsonMap(), techContact.toJsonMap()));
    Map<String, Object> response =
        action.handleJsonRequest(ImmutableMap.of("op", "update", "id", CLIENT_ID, "args", reqJson));
    assertThat(response).containsEntry("status", "ERROR");
    assertThat(response)
        .containsEntry(
            "message", "An abuse contact visible in domain WHOIS query must be designated");
    assertMetric(CLIENT_ID, "update", "[OWNER]", "ERROR: ContactRequirementException");
  }

  @Test
  public void testPost_updateContacts_whoisAbuseContactMustHavePhoneNumber_error() {
    // First make the contact's info visible in whois as abuse contact info.
    Registrar registrar = loadRegistrar(CLIENT_ID);
    RegistrarContact rc =
        AppEngineRule.makeRegistrarContact2()
            .asBuilder()
            .setVisibleInDomainWhoisAsAbuse(true)
            .build();
    // Lest we anger the timestamp inversion bug.
    // (we also update the registrar so we get the timestamp right)
    registrar = persistResource(registrar);
    persistSimpleResource(rc);

    // Now try to set the phone number to null.
    rc = rc.asBuilder().setPhoneNumber(null).build();
    Map<String, Object> reqJson = registrar.toJsonMap();
    reqJson.put("contacts", ImmutableList.of(rc.toJsonMap(), techContact.toJsonMap()));
    Map<String, Object> response =
        action.handleJsonRequest(ImmutableMap.of("op", "update", "id", CLIENT_ID, "args", reqJson));
    assertThat(response).containsEntry("status", "ERROR");
    assertThat(response)
        .containsEntry(
            "message", "The abuse contact visible in domain WHOIS query must have a phone number");
    assertMetric(CLIENT_ID, "update", "[OWNER]", "ERROR: ContactRequirementException");
  }

  @Test
  public void testSuccess_setRegistryLockPassword() {
    addPasswordToTechContact();
    techContact = Iterables.getOnlyElement(loadRegistrar(CLIENT_ID).getContactsOfType(Type.TECH));
    assertThat(techContact.verifyRegistryLockPassword("hi")).isTrue();
    assertMetric(CLIENT_ID, "update", "[OWNER]", "SUCCESS");
  }

  @Test
  public void testSuccess_setRegistryLockPassword_notOverriddenLater() {
    addPasswordToTechContact();
    techContact = Iterables.getOnlyElement(loadRegistrar(CLIENT_ID).getContactsOfType(Type.TECH));
    assertThat(techContact.verifyRegistryLockPassword("hi")).isTrue();

    techContact = Iterables.getOnlyElement(loadRegistrar(CLIENT_ID).getContactsOfType(Type.TECH));
    Map<String, Object> techContactMap = techContact.toJsonMap();
    techContactMap.put("name", "Some Other Name");
    Map<String, Object> reqJson = loadRegistrar(CLIENT_ID).toJsonMap();
    reqJson.put(
        "contacts",
        ImmutableList.of(AppEngineRule.makeRegistrarContact2().toJsonMap(), techContactMap));
    Map<String, Object> response =
        action.handleJsonRequest(ImmutableMap.of("op", "update", "id", CLIENT_ID, "args", reqJson));
    assertThat(response).containsAtLeastEntriesIn(ImmutableMap.of("status", "SUCCESS"));
    techContact = Iterables.getOnlyElement(loadRegistrar(CLIENT_ID).getContactsOfType(Type.TECH));
    assertThat(techContact.verifyRegistryLockPassword("hi")).isTrue();
  }

  private void addPasswordToTechContact() {
    techContact =
        persistResource(techContact.asBuilder().setAllowedToSetRegistryLockPassword(true).build());
    Map<String, Object> contactMap = techContact.toJsonMap();
    contactMap.put("registryLockPassword", "hi");
    Map<String, Object> reqJson = loadRegistrar(CLIENT_ID).toJsonMap();
    reqJson.put(
        "contacts",
        ImmutableList.of(AppEngineRule.makeRegistrarContact2().toJsonMap(), contactMap));
    Map<String, Object> response =
        action.handleJsonRequest(ImmutableMap.of("op", "update", "id", CLIENT_ID, "args", reqJson));
    assertThat(response).containsAtLeastEntriesIn(ImmutableMap.of("status", "SUCCESS"));
  }

  @Test
  public void testPost_failure_setRegistryLockPassword_newContact() {
    Map<String, Object> reqJson = loadRegistrar(CLIENT_ID).toJsonMap();
    reqJson.put(
        "contacts",
        ImmutableList.of(
            AppEngineRule.makeRegistrarContact2()
                .asBuilder()
                .setEmailAddress("someotheremail@example.com")
                .setRegistryLockEmailAddress("someotherexample@example.com")
                .setAllowedToSetRegistryLockPassword(true)
                .build()
                .toJsonMap(),
            techContact.toJsonMap()));

    Map<String, Object> response =
        action.handleJsonRequest(ImmutableMap.of("op", "update", "id", CLIENT_ID, "args", reqJson));
    assertThat(response)
        .containsExactly(
            "status",
            "ERROR",
            "results",
            ImmutableList.of(),
            "message",
            "Not allowed to set registry lock password directly on new contact");
    assertMetric(CLIENT_ID, "update", "[OWNER]", "ERROR: FormException");
  }

  @Test
  public void testPost_failure_setRegistryLockPassword_notAllowed() {
    // "allowedToSetRegistryLockPassword" must be set through the back end first
    // before we can set a password through the UI
    Map<String, Object> contactMap =
        techContact.asBuilder().setAllowedToSetRegistryLockPassword(true).build().toJsonMap();
    contactMap.put("registryLockPassword", "hi");
    Map<String, Object> reqJson = loadRegistrar(CLIENT_ID).toJsonMap();
    reqJson.put(
        "contacts",
        ImmutableList.of(AppEngineRule.makeRegistrarContact2().toJsonMap(), contactMap));

    Map<String, Object> response =
        action.handleJsonRequest(ImmutableMap.of("op", "update", "id", CLIENT_ID, "args", reqJson));
    assertThat(response)
        .containsExactly(
            "status",
            "ERROR",
            "results",
            ImmutableList.of(),
            "message",
            "Registrar contact not allowed to set registry lock password");
    assertMetric(CLIENT_ID, "update", "[OWNER]", "ERROR: FormException");
  }

  @Test
  public void testPost_failure_setRegistryLockAllowed() {
    // One cannot set the "isAllowedToSetRegistryLockPassword" field through the UI
    Map<String, Object> reqJson = loadRegistrar(CLIENT_ID).toJsonMap();
    reqJson.put(
        "contacts",
        ImmutableList.of(
            AppEngineRule.makeRegistrarContact2().toJsonMap(),
            techContact.asBuilder().setAllowedToSetRegistryLockPassword(true).build().toJsonMap()));

    Map<String, Object> response =
        action.handleJsonRequest(ImmutableMap.of("op", "update", "id", CLIENT_ID, "args", reqJson));
    assertThat(response)
        .containsExactly(
            "status",
            "ERROR",
            "results",
            ImmutableList.of(),
            "message",
            "Cannot set isAllowedToSetRegistryLockPassword through UI");
    assertMetric(CLIENT_ID, "update", "[OWNER]", "ERROR: FormException");
  }
}
