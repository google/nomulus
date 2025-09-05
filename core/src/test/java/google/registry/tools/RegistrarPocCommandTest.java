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
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.model.registrar.RegistrarPoc.Type.ABUSE;
import static google.registry.model.registrar.RegistrarPoc.Type.ADMIN;
import static google.registry.model.registrar.RegistrarPoc.Type.TECH;
import static google.registry.model.registrar.RegistrarPoc.Type.WHOIS;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistResources;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RegistrarPocCommand}. */
class RegistrarPocCommandTest extends CommandTestCase<RegistrarPocCommand> {

  private String output;

  @BeforeEach
  void beforeEach() {
    output = tmpDir.resolve("temp.dat").toString();
  }

  @Test
  void testList() throws Exception {
    Registrar registrar = loadRegistrar("NewRegistrar");
    tm().transact(
            () ->
                RegistrarPoc.updateContacts(
                    registrar,
                    ImmutableSet.of(
                        new RegistrarPoc.Builder()
                            .setRegistrar(registrar)
                            .setName("John Doe")
                            .setEmailAddress("john.doe@example.com")
                            .setTypes(ImmutableSet.of(ADMIN))
                            .setVisibleInWhoisAsAdmin(true)
                            .build())));
    runCommandForced("--mode=LIST", "--output=" + output, "NewRegistrar");
    assertThat(Files.readAllLines(Paths.get(output), UTF_8))
        .containsExactly(
            "John Doe",
            "john.doe@example.com",
            "Types: [ADMIN]",
            "Visible in registrar WHOIS query as Admin contact: Yes",
            "Visible in registrar WHOIS query as Technical contact: No",
            "Phone number and email visible in domain WHOIS query as "
                + "Registrar Abuse contact info: No");
  }

  @Test
  void testUpdate() throws Exception {
    Registrar registrar = loadRegistrar("NewRegistrar");
    ImmutableList<RegistrarPoc> contacts =
        ImmutableList.of(
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("Judith Doe")
                .setEmailAddress("judith.doe@example.com")
                .setTypes(ImmutableSet.of(WHOIS))
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(true)
                .setVisibleInDomainWhoisAsAbuse(false)
                .build());
    persistResources(contacts);
    runCommandForced(
        "--mode=UPDATE",
        "--name=Judith Registrar",
        "--email=judith.doe@example.com",
        "--phone=+1.2125650000",
        "--fax=+1.2125650001",
        "--contact_type=WHOIS",
        "--visible_in_whois_as_admin=true",
        "--visible_in_whois_as_tech=false",
        "--visible_in_domain_whois_as_abuse=false",
        "NewRegistrar");
    RegistrarPoc registrarPoc = loadRegistrar("NewRegistrar").getContacts().asList().get(1);
    assertAboutImmutableObjects()
        .that(registrarPoc)
        .isEqualExceptFields(
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("Judith Registrar")
                .setEmailAddress("judith.doe@example.com")
                .setPhoneNumber("+1.2125650000")
                .setFaxNumber("+1.2125650001")
                .setTypes(ImmutableSet.of(WHOIS))
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(false)
                .setVisibleInDomainWhoisAsAbuse(false)
                .build(),
            "id");
  }

  @Test
  void testUpdate_unsetOtherWhoisAbuseFlags() throws Exception {
    Registrar registrar = loadRegistrar("NewRegistrar");
    persistResource(
        new RegistrarPoc.Builder()
            .setRegistrar(registrar)
            .setName("John Doe")
            .setEmailAddress("john.doe@example.com")
            .build());
    persistResource(
        new RegistrarPoc.Builder()
            .setRegistrar(registrar)
            .setName("Johnna Doe")
            .setEmailAddress("johnna.doe@example.com")
            .setVisibleInDomainWhoisAsAbuse(true)
            .build());
    runCommandForced(
        "--mode=UPDATE",
        "--email=john.doe@example.com",
        "--visible_in_domain_whois_as_abuse=true",
        "NewRegistrar");
    ImmutableList<RegistrarPoc> registrarPocs =
        loadRegistrar("NewRegistrar").getContacts().asList();
    for (RegistrarPoc registrarPoc : registrarPocs) {
      if ("John Doe".equals(registrarPoc.getName())) {
        assertThat(registrarPoc.getVisibleInDomainWhoisAsAbuse()).isTrue();
      } else {
        assertThat(registrarPoc.getVisibleInDomainWhoisAsAbuse()).isFalse();
      }
    }
  }

  @Test
  void testUpdate_cannotUnsetOnlyWhoisAbuseContact() {
    Registrar registrar = loadRegistrar("NewRegistrar");
    persistResource(
        new RegistrarPoc.Builder()
            .setRegistrar(registrar)
            .setName("John Doe")
            .setEmailAddress("john.doe@example.com")
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
    RegistrarPoc registrarPoc = loadRegistrar("NewRegistrar").getContacts().asList().get(1);
    assertThat(registrarPoc.getVisibleInDomainWhoisAsAbuse()).isTrue();
  }

  @Test
  void testUpdate_emptyCommandModifiesNothing() throws Exception {
    Registrar registrar = loadRegistrar("NewRegistrar");
    RegistrarPoc existingContact =
        persistResource(
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("John Doe")
                .setEmailAddress("john.doe@example.com")
                .setPhoneNumber("123-456-7890")
                .setFaxNumber("123-456-7890")
                .setTypes(ImmutableSet.of(ADMIN, ABUSE))
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(true)
                .setVisibleInDomainWhoisAsAbuse(true)
                .build());
    runCommandForced("--mode=UPDATE", "--email=john.doe@example.com", "NewRegistrar");
    RegistrarPoc registrarPoc = loadRegistrar("NewRegistrar").getContacts().asList().get(1);
    assertThat(registrarPoc.getEmailAddress()).isEqualTo(existingContact.getEmailAddress());
    assertThat(registrarPoc.getName()).isEqualTo(existingContact.getName());
    assertThat(registrarPoc.getPhoneNumber()).isEqualTo(existingContact.getPhoneNumber());
    assertThat(registrarPoc.getFaxNumber()).isEqualTo(existingContact.getFaxNumber());
    assertThat(registrarPoc.getTypes()).isEqualTo(existingContact.getTypes());
    assertThat(registrarPoc.getVisibleInWhoisAsAdmin())
        .isEqualTo(existingContact.getVisibleInWhoisAsAdmin());
    assertThat(registrarPoc.getVisibleInWhoisAsTech())
        .isEqualTo(existingContact.getVisibleInWhoisAsTech());
    assertThat(registrarPoc.getVisibleInDomainWhoisAsAbuse())
        .isEqualTo(existingContact.getVisibleInDomainWhoisAsAbuse());
  }

  @Test
  void testUpdate_listOfTypesWorks() throws Exception {
    Registrar registrar = loadRegistrar("NewRegistrar");
    persistResource(
        new RegistrarPoc.Builder()
            .setRegistrar(registrar)
            .setName("John Doe")
            .setEmailAddress("john.doe@example.com")
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
    RegistrarPoc registrarPoc = loadRegistrar("NewRegistrar").getContacts().asList().get(1);
    assertThat(registrarPoc.getTypes()).containsExactly(ADMIN, TECH);
  }

  @Test
  void testUpdate_clearAllTypes() throws Exception {
    Registrar registrar = loadRegistrar("NewRegistrar");
    persistResource(
        new RegistrarPoc.Builder()
            .setRegistrar(registrar)
            .setName("John Doe")
            .setEmailAddress("john.doe@example.com")
            .setTypes(ImmutableSet.of(ADMIN, ABUSE))
            .build());
    runCommandForced(
        "--mode=UPDATE", "--email=john.doe@example.com", "--contact_type=", "NewRegistrar");
    RegistrarPoc registrarPoc = loadRegistrar("NewRegistrar").getContacts().asList().get(1);
    assertThat(registrarPoc.getTypes()).isEmpty();
  }

  @Test
  void testCreate_withAdminType() throws Exception {
    Registrar registrar = loadRegistrar("NewRegistrar");
    runCommandForced(
        "--mode=CREATE",
        "--name=Jim Doe",
        "--email=jim.doe@example.com",
        "--contact_type=ADMIN,ABUSE",
        "--visible_in_whois_as_admin=true",
        "--visible_in_whois_as_tech=false",
        "--visible_in_domain_whois_as_abuse=true",
        "NewRegistrar");
    RegistrarPoc registrarPoc = loadRegistrar("NewRegistrar").getContacts().asList().get(1);
    assertAboutImmutableObjects()
        .that(registrarPoc)
        .isEqualExceptFields(
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("Jim Doe")
                .setEmailAddress("jim.doe@example.com")
                .setTypes(ImmutableSet.of(ADMIN, ABUSE))
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(false)
                .setVisibleInDomainWhoisAsAbuse(true)
                .build(),
            "id");
  }

  @Test
  void testDelete() throws Exception {
    assertThat(loadRegistrar("NewRegistrar").getContacts()).isNotEmpty();
    runCommandForced("--mode=DELETE", "--email=janedoe@theregistrar.com", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getContacts()).isEmpty();
  }

  @Test
  void testDelete_failsOnDomainWhoisAbuseContact() {
    RegistrarPoc registrarPoc = loadRegistrar("NewRegistrar").getContacts().asList().getFirst();
    persistResource(registrarPoc.asBuilder().setVisibleInDomainWhoisAsAbuse(true).build());
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
  void testCreate_withNoContactTypes() throws Exception {
    runCommandForced(
        "--mode=CREATE", "--name=Jim Doe", "--email=jim.doe@example.com", "NewRegistrar");
    RegistrarPoc registrarPoc = loadRegistrar("NewRegistrar").getContacts().asList().get(1);
    assertThat(registrarPoc.getTypes()).isEmpty();
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
