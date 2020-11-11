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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.registrar.RegistrarContact.Type.ABUSE;
import static google.registry.model.registrar.RegistrarContact.Type.ADMIN;
import static google.registry.model.registrar.RegistrarContact.Type.TECH;
import static google.registry.model.registrar.RegistrarContact.Type.WHOIS;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DatastoreHelper.persistSimpleResource;
import static google.registry.testing.DatastoreHelper.persistSimpleResources;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RegistrarContactCommand}. */
class RegistrarContactCommandTest extends CommandTestCase<RegistrarContactCommand> {

  private String output;

  @BeforeEach
  void beforeEach() {
    output = tmpDir.resolve("temp.dat").toString();
  }

  @Test
  void testCloudSqlPersistence() throws Exception {
    Registrar registrar = saveRegistrar("NewRegistrar");
    runCommandForced(
        "--mode=CREATE", "--name=Jim Doe", "--email=jim.doe@example.com", "NewRegistrar");
    RegistrarContact persisted =
        jpaTm()
            .transact(
                () -> jpaTm().load(RegistrarContact.createVKey(registrar, "jim.doe@example.com")));
    assertThat("Jim Doe").isEqualTo(persisted.getName());
    assertThat("jim.doe@example.com").isEqualTo(persisted.getEmailAddress());
    assertThat("NewRegistrar").isEqualTo(persisted.getRegistrarId());
  }

  @Test
  void testList() throws Exception {
    Registrar registrar = loadRegistrar("NewRegistrar");
    RegistrarContact.updateContacts(
        registrar,
        ImmutableSet.of(
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("John Doe")
                .setEmailAddress("john.doe@example.com")
                .setTypes(ImmutableSet.of(ADMIN))
                .setVisibleInWhoisAsAdmin(true)
                .build()));
    runCommandForced("--mode=LIST", "--output=" + output, "NewRegistrar");
    assertThat(Files.readAllLines(Paths.get(output), UTF_8))
        .containsExactly(
            "John Doe",
            "john.doe@example.com",
            "Types: [ADMIN]",
            "Visible in registrar WHOIS query as Admin contact: Yes",
            "Visible in registrar WHOIS query as Technical contact: No",
            "Phone number and email visible in domain WHOIS query as "
                + "Registrar Abuse contact info: No",
            "Registrar-Console access: No");
  }

  @Test
  void testUpdate() throws Exception {
    Registrar registrar = loadRegistrar("NewRegistrar");
    ImmutableList<RegistrarContact> contacts = ImmutableList.of(
        new RegistrarContact.Builder()
            .setParent(registrar)
            .setName("Judith Doe")
            .setEmailAddress("judith.doe@example.com")
            .setTypes(ImmutableSet.of(WHOIS))
            .setVisibleInWhoisAsAdmin(true)
            .setVisibleInWhoisAsTech(true)
            .setVisibleInDomainWhoisAsAbuse(false)
            .build());
    persistSimpleResources(contacts);
    runCommandForced(
        "--mode=UPDATE",
        "--name=Judith Registrar",
        "--email=judith.doe@example.com",
        "--registry_lock_email=judith.doe@external.com",
        "--phone=+1.2125650000",
        "--fax=+1.2125650001",
        "--contact_type=WHOIS",
        "--visible_in_whois_as_admin=true",
        "--visible_in_whois_as_tech=false",
        "--visible_in_domain_whois_as_abuse=false",
        "NewRegistrar");
    RegistrarContact registrarContact = loadRegistrar("NewRegistrar").getContacts().asList().get(1);
    assertThat(registrarContact)
        .isEqualTo(
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("Judith Registrar")
                .setEmailAddress("judith.doe@example.com")
                .setRegistryLockEmailAddress("judith.doe@external.com")
                .setPhoneNumber("+1.2125650000")
                .setFaxNumber("+1.2125650001")
                .setTypes(ImmutableSet.of(WHOIS))
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(false)
                .setVisibleInDomainWhoisAsAbuse(false)
                .build());
  }

  @Test
  void testUpdate_enableConsoleAccess() throws Exception {
    Registrar registrar = loadRegistrar("NewRegistrar");
    persistSimpleResource(
        new RegistrarContact.Builder()
            .setParent(registrar)
            .setName("Jane Doe")
            .setEmailAddress("jane.doe@example.com")
            .build());
    runCommandForced(
        "--mode=UPDATE",
        "--email=jane.doe@example.com",
        "--allow_console_access=true",
        "NewRegistrar");
    RegistrarContact registrarContact =
        loadRegistrar("NewRegistrar").getContacts().stream()
            .filter(rc -> rc.getEmailAddress().equals("jane.doe@example.com"))
            .findFirst()
            .get();
    assertThat(registrarContact.getGaeUserId()).matches("-?[0-9]+");
  }

  @Test
  void testUpdate_disableConsoleAccess() throws Exception {
    Registrar registrar = loadRegistrar("NewRegistrar");
    persistSimpleResource(
        new RegistrarContact.Builder()
            .setParent(registrar)
            .setName("Judith Doe")
            .setEmailAddress("judith.doe@example.com")
            .setGaeUserId("11111")
            .build());
    runCommandForced(
        "--mode=UPDATE",
        "--email=judith.doe@example.com",
        "--allow_console_access=false",
        "NewRegistrar");
    RegistrarContact registrarContact = loadRegistrar("NewRegistrar").getContacts().asList().get(1);
    assertThat(registrarContact.getGaeUserId()).isNull();
  }

  @Test
  void testUpdate_unsetOtherWhoisAbuseFlags() throws Exception {
    Registrar registrar = loadRegistrar("NewRegistrar");
    persistSimpleResource(
        new RegistrarContact.Builder()
            .setParent(registrar)
            .setName("John Doe")
            .setEmailAddress("john.doe@example.com")
            .setGaeUserId("11111")
            .build());
    persistSimpleResource(
        new RegistrarContact.Builder()
            .setParent(registrar)
            .setName("Johnna Doe")
            .setEmailAddress("johnna.doe@example.com")
            .setGaeUserId("11112")
            .setVisibleInDomainWhoisAsAbuse(true)
            .build());
    runCommandForced(
        "--mode=UPDATE",
        "--email=john.doe@example.com",
        "--visible_in_domain_whois_as_abuse=true",
        "NewRegistrar");
    ImmutableList<RegistrarContact> registrarContacts =
        loadRegistrar("NewRegistrar").getContacts().asList();
    for (RegistrarContact registrarContact : registrarContacts) {
      if (registrarContact.getName().equals("John Doe")) {
        assertThat(registrarContact.getVisibleInDomainWhoisAsAbuse()).isTrue();
      } else {
        assertThat(registrarContact.getVisibleInDomainWhoisAsAbuse()).isFalse();
      }
    }
  }

  @Test
  void testUpdate_cannotUnsetOnlyWhoisAbuseContact() {
    Registrar registrar = loadRegistrar("NewRegistrar");
    persistSimpleResource(
        new RegistrarContact.Builder()
            .setParent(registrar)
            .setName("John Doe")
            .setEmailAddress("john.doe@example.com")
            .setGaeUserId("11111")
            .setVisibleInDomainWhoisAsAbuse(true)
            .build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--mode=UPDATE",
                    "--email=john.doe@example.com",
                    "--visible_in_domain_whois_as_abuse=false",
                    "NewRegistrar"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Cannot clear visible_in_domain_whois_as_abuse flag");
    RegistrarContact registrarContact = loadRegistrar("NewRegistrar").getContacts().asList().get(1);
    assertThat(registrarContact.getVisibleInDomainWhoisAsAbuse()).isTrue();
  }

  @Test
  void testUpdate_emptyCommandModifiesNothing() throws Exception {
    Registrar registrar = loadRegistrar("NewRegistrar");
    RegistrarContact existingContact = persistSimpleResource(
        new RegistrarContact.Builder()
            .setParent(registrar)
            .setName("John Doe")
            .setEmailAddress("john.doe@example.com")
            .setGaeUserId("11111")
            .setPhoneNumber("123-456-7890")
            .setFaxNumber("123-456-7890")
            .setTypes(ImmutableSet.of(ADMIN, ABUSE))
            .setVisibleInWhoisAsAdmin(true)
            .setVisibleInWhoisAsTech(true)
            .setVisibleInDomainWhoisAsAbuse(true)
            .build());
    runCommandForced("--mode=UPDATE", "--email=john.doe@example.com", "NewRegistrar");
    RegistrarContact registrarContact = loadRegistrar("NewRegistrar").getContacts().asList().get(1);
    assertThat(registrarContact.getEmailAddress()).isEqualTo(existingContact.getEmailAddress());
    assertThat(registrarContact.getName()).isEqualTo(existingContact.getName());
    assertThat(registrarContact.getGaeUserId()).isEqualTo(existingContact.getGaeUserId());
    assertThat(registrarContact.getPhoneNumber()).isEqualTo(existingContact.getPhoneNumber());
    assertThat(registrarContact.getFaxNumber()).isEqualTo(existingContact.getFaxNumber());
    assertThat(registrarContact.getTypes()).isEqualTo(existingContact.getTypes());
    assertThat(registrarContact.getVisibleInWhoisAsAdmin())
        .isEqualTo(existingContact.getVisibleInWhoisAsAdmin());
    assertThat(registrarContact.getVisibleInWhoisAsTech())
        .isEqualTo(existingContact.getVisibleInWhoisAsTech());
    assertThat(registrarContact.getVisibleInDomainWhoisAsAbuse())
        .isEqualTo(existingContact.getVisibleInDomainWhoisAsAbuse());
  }

  @Test
  void testUpdate_listOfTypesWorks() throws Exception {
    Registrar registrar = loadRegistrar("NewRegistrar");
    persistSimpleResource(
        new RegistrarContact.Builder()
            .setParent(registrar)
            .setName("John Doe")
            .setEmailAddress("john.doe@example.com")
            .setGaeUserId("11111")
            .setPhoneNumber("123-456-7890")
            .setFaxNumber("123-456-7890")
            .setTypes(ImmutableSet.of(ADMIN, ABUSE))
            .setVisibleInWhoisAsAdmin(true)
            .setVisibleInWhoisAsTech(true)
            .setVisibleInDomainWhoisAsAbuse(true)
            .build());
    runCommandForced(
        "--mode=UPDATE",
        "--email=john.doe@example.com",
        "--contact_type=ADMIN,TECH",
        "NewRegistrar");
    RegistrarContact registrarContact = loadRegistrar("NewRegistrar").getContacts().asList().get(1);
    assertThat(registrarContact.getTypes()).containsExactly(ADMIN, TECH);
  }

  @Test
  void testUpdate_clearAllTypes() throws Exception {
    Registrar registrar = loadRegistrar("NewRegistrar");
    persistSimpleResource(
        new RegistrarContact.Builder()
            .setParent(registrar)
            .setName("John Doe")
            .setEmailAddress("john.doe@example.com")
            .setTypes(ImmutableSet.of(ADMIN, ABUSE))
            .build());
    runCommandForced(
        "--mode=UPDATE",
        "--email=john.doe@example.com",
        "--contact_type=",
        "NewRegistrar");
    RegistrarContact registrarContact = loadRegistrar("NewRegistrar").getContacts().asList().get(1);
    assertThat(registrarContact.getTypes()).isEmpty();
  }

  @Test
  void testCreate_withAdminType() throws Exception {
    Registrar registrar = loadRegistrar("NewRegistrar");
    runCommandForced(
        "--mode=CREATE",
        "--name=Jim Doe",
        "--email=jim.doe@example.com",
        "--registry_lock_email=jim.doe@external.com",
        "--contact_type=ADMIN,ABUSE",
        "--visible_in_whois_as_admin=true",
        "--visible_in_whois_as_tech=false",
        "--visible_in_domain_whois_as_abuse=true",
        "NewRegistrar");
    RegistrarContact registrarContact = loadRegistrar("NewRegistrar").getContacts().asList().get(1);
    assertThat(registrarContact)
        .isEqualTo(
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("Jim Doe")
                .setEmailAddress("jim.doe@example.com")
                .setRegistryLockEmailAddress("jim.doe@external.com")
                .setTypes(ImmutableSet.of(ADMIN, ABUSE))
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(false)
                .setVisibleInDomainWhoisAsAbuse(true)
                .build());
    assertThat(registrarContact.getGaeUserId()).isNull();
  }

  @Test
  void testDelete() throws Exception {
    assertThat(loadRegistrar("NewRegistrar").getContacts()).isNotEmpty();
    runCommandForced(
        "--mode=DELETE",
        "--email=janedoe@theregistrar.com",
        "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getContacts()).isEmpty();
  }

  @Test
  void testDelete_failsOnDomainWhoisAbuseContact() {
    RegistrarContact registrarContact = loadRegistrar("NewRegistrar").getContacts().asList().get(0);
    persistSimpleResource(
        registrarContact.asBuilder().setVisibleInDomainWhoisAsAbuse(true).build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--mode=DELETE", "--email=janedoe@theregistrar.com", "NewRegistrar"));
    assertThat(thrown).hasMessageThat().contains("Cannot delete the domain WHOIS abuse contact");
    assertThat(loadRegistrar("NewRegistrar").getContacts()).isNotEmpty();
  }

  @Test
  void testCreate_withConsoleAccessEnabled() throws Exception {
    runCommandForced(
        "--mode=CREATE",
        "--name=Jim Doe",
        "--email=jim.doe@example.com",
        "--allow_console_access=true",
        "--contact_type=ADMIN,ABUSE",
        "NewRegistrar");
    RegistrarContact registrarContact = loadRegistrar("NewRegistrar").getContacts().asList().get(1);
    assertThat(registrarContact.getGaeUserId()).matches("-?[0-9]+");
  }

  @Test
  void testCreate_withNoContactTypes() throws Exception {
    runCommandForced(
        "--mode=CREATE", "--name=Jim Doe", "--email=jim.doe@example.com", "NewRegistrar");
    RegistrarContact registrarContact = loadRegistrar("NewRegistrar").getContacts().asList().get(1);
    assertThat(registrarContact.getTypes()).isEmpty();
  }

  @Test
  void testCreate_syncingRequiredSetToTrue() throws Exception {
    persistResource(
        loadRegistrar("NewRegistrar").asBuilder().setContactsRequireSyncing(false).build());

    assertThat(loadRegistrar("NewRegistrar").getContactsRequireSyncing()).isFalse();
    runCommandForced(
        "--mode=CREATE", "--name=Jim Doe", "--email=jim.doe@example.com", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getContactsRequireSyncing()).isTrue();
  }

  @Test
  void testCreate_setAllowedToSetRegistryLockPassword() throws Exception {
    runCommandForced(
        "--mode=CREATE",
        "--name=Jim Doe",
        "--email=jim.doe@example.com",
        "--registry_lock_email=jim.doe.registry.lock@example.com",
        "--allowed_to_set_registry_lock_password=true",
        "NewRegistrar");
    RegistrarContact registrarContact = loadRegistrar("NewRegistrar").getContacts().asList().get(1);
    assertThat(registrarContact.isAllowedToSetRegistryLockPassword()).isTrue();
    registrarContact.asBuilder().setRegistryLockPassword("foo");
  }

  @Test
  void testUpdate_setAllowedToSetRegistryLockPassword() throws Exception {
    Registrar registrar = loadRegistrar("NewRegistrar");
    RegistrarContact registrarContact =
        persistSimpleResource(
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("Jim Doe")
                .setEmailAddress("jim.doe@example.com")
                .build());
    assertThat(registrarContact.isAllowedToSetRegistryLockPassword()).isFalse();

    // First, try (and fail) to set the password directly
    assertThrows(
        IllegalArgumentException.class,
        () -> registrarContact.asBuilder().setRegistryLockPassword("foo"));

    // Next, try (and fail) to allow registry lock without a registry lock email
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    runCommandForced(
                        "--mode=UPDATE",
                        "--email=jim.doe@example.com",
                        "--allowed_to_set_registry_lock_password=true",
                        "NewRegistrar")))
        .hasMessageThat()
        .isEqualTo("Registry lock email must not be null if allowing registry lock access");

    // Next, include the email and it should succeed
    runCommandForced(
        "--mode=UPDATE",
        "--email=jim.doe@example.com",
        "--registry_lock_email=jim.doe.registry.lock@example.com",
        "--allowed_to_set_registry_lock_password=true",
        "NewRegistrar");
    RegistrarContact newContact = reloadResource(registrarContact);
    assertThat(newContact.isAllowedToSetRegistryLockPassword()).isTrue();
    // should be allowed to set the password now
    newContact.asBuilder().setRegistryLockPassword("foo");
  }

  @Test
  void testUpdate_setAllowedToSetRegistryLockPassword_removesOldPassword() throws Exception {
    Registrar registrar = loadRegistrar("NewRegistrar");
    RegistrarContact registrarContact =
        persistSimpleResource(
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("Jim Doe")
                .setEmailAddress("jim.doe@example.com")
                .setRegistryLockEmailAddress("jim.doe.registry.lock@example.com")
                .setAllowedToSetRegistryLockPassword(true)
                .setRegistryLockPassword("hi")
                .build());
    assertThat(registrarContact.verifyRegistryLockPassword("hi")).isTrue();
    assertThat(registrarContact.verifyRegistryLockPassword("hello")).isFalse();
    runCommandForced(
        "--mode=UPDATE",
        "--email=jim.doe@example.com",
        "--allowed_to_set_registry_lock_password=true",
        "NewRegistrar");
    registrarContact = reloadResource(registrarContact);
    assertThat(registrarContact.verifyRegistryLockPassword("hi")).isFalse();
  }

  @Test
  void testCreate_failure_badEmail() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--mode=CREATE", "--name=Jim Doe", "--email=lolcat", "NewRegistrar"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Provided email lolcat is not a valid email address");
  }

  @Test
  void testCreate_failure_nullEmail() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--mode=CREATE", "--name=Jim Doe", "NewRegistrar"));
    assertThat(thrown).hasMessageThat().isEqualTo("--email is required when --mode=CREATE");
  }
}
