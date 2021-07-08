// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.registry.Registry.TldState.GENERAL_AVAILABILITY;
import static google.registry.model.registry.Registry.TldState.START_DATE_SUNRISE;
import static google.registry.testing.AppEngineExtension.makeRegistrar1;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT_HASH;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistSimpleResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldState;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import google.registry.util.CidrAddressBlock;
import google.registry.util.SystemClock;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

@DualDatabaseTest
public final class OteAccountBuilderTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @TestOfyAndSql
  void testGetRegistrarToTldMap() {
    assertThat(OteAccountBuilder.forClientId("myclientid").getClientIdToTldMap())
        .containsExactly(
            "myclientid-1", "myclientid-sunrise",
            "myclientid-3", "myclientid-ga",
            "myclientid-4", "myclientid-ga",
            "myclientid-5", "myclientid-eap");
  }

  @BeforeEach
  void beforeEach() {
    persistPremiumList("default_sandbox_list", "sandbox,USD 1000");
  }

  private void assertTldExists(String tld, TldState tldState, Money eapFee) {
    Registry registry = Registry.get(tld);
    assertThat(registry).isNotNull();
    assertThat(registry.getPremiumListName()).hasValue("default_sandbox_list");
    assertThat(registry.getTldStateTransitions()).containsExactly(START_OF_TIME, tldState);
    assertThat(registry.getDnsWriters()).containsExactly("VoidDnsWriter");
    assertThat(registry.getAddGracePeriodLength()).isEqualTo(Duration.standardHours(1));
    assertThat(registry.getPendingDeleteLength()).isEqualTo(Duration.standardMinutes(5));
    assertThat(registry.getRedemptionGracePeriodLength()).isEqualTo(Duration.standardMinutes(10));
    assertThat(registry.getCurrency()).isEqualTo(eapFee.getCurrencyUnit());
    // This uses "now" on purpose - so the test will break at 2022 when the current EapFee in OTE
    // goes back to 0
    assertThat(registry.getEapFeeFor(DateTime.now(DateTimeZone.UTC)).getCost())
        .isEqualTo(eapFee.getAmount());
  }

  private void assertRegistrarExists(String clientId, String tld) {
    Registrar registrar = Registrar.loadByClientId(clientId).orElse(null);
    assertThat(registrar).isNotNull();
    assertThat(registrar.getType()).isEqualTo(Registrar.Type.OTE);
    assertThat(registrar.getState()).isEqualTo(Registrar.State.ACTIVE);
    assertThat(registrar.getAllowedTlds()).containsExactly(tld);
  }

  private void assertContactExists(String clientId, String email) {
    Registrar registrar = Registrar.loadByClientId(clientId).get();
    assertThat(registrar.getContacts().stream().map(RegistrarContact::getEmailAddress))
        .contains(email);
    RegistrarContact contact =
        registrar.getContacts().stream()
            .filter(c -> email.equals(c.getEmailAddress()))
            .findAny()
            .get();
    assertThat(contact.getEmailAddress()).isEqualTo(email);
    assertThat(contact.getGaeUserId()).isNotEmpty();
  }

  @TestOfyAndSql
  void testCreateOteEntities_success() {
    OteAccountBuilder.forClientId("myclientid").addContact("email@example.com").buildAndPersist();

    assertTldExists("myclientid-sunrise", START_DATE_SUNRISE, Money.zero(USD));
    assertTldExists("myclientid-ga", GENERAL_AVAILABILITY, Money.zero(USD));
    assertTldExists("myclientid-eap", GENERAL_AVAILABILITY, Money.of(USD, 100));
    assertRegistrarExists("myclientid-1", "myclientid-sunrise");
    assertRegistrarExists("myclientid-3", "myclientid-ga");
    assertRegistrarExists("myclientid-4", "myclientid-ga");
    assertRegistrarExists("myclientid-5", "myclientid-eap");
    assertContactExists("myclientid-1", "email@example.com");
    assertContactExists("myclientid-3", "email@example.com");
    assertContactExists("myclientid-4", "email@example.com");
    assertContactExists("myclientid-5", "email@example.com");
  }

  @TestOfyAndSql
  void testCreateOteEntities_multipleContacts_success() {
    OteAccountBuilder.forClientId("myclientid")
        .addContact("email@example.com")
        .addContact("other@example.com")
        .addContact("someone@example.com")
        .buildAndPersist();

    assertTldExists("myclientid-sunrise", START_DATE_SUNRISE, Money.zero(USD));
    assertTldExists("myclientid-ga", GENERAL_AVAILABILITY, Money.zero(USD));
    assertTldExists("myclientid-eap", GENERAL_AVAILABILITY, Money.of(USD, 100));
    assertRegistrarExists("myclientid-1", "myclientid-sunrise");
    assertRegistrarExists("myclientid-3", "myclientid-ga");
    assertRegistrarExists("myclientid-4", "myclientid-ga");
    assertRegistrarExists("myclientid-5", "myclientid-eap");
    assertContactExists("myclientid-1", "email@example.com");
    assertContactExists("myclientid-3", "email@example.com");
    assertContactExists("myclientid-4", "email@example.com");
    assertContactExists("myclientid-5", "email@example.com");
    assertContactExists("myclientid-1", "other@example.com");
    assertContactExists("myclientid-3", "other@example.com");
    assertContactExists("myclientid-4", "other@example.com");
    assertContactExists("myclientid-5", "other@example.com");
    assertContactExists("myclientid-1", "someone@example.com");
    assertContactExists("myclientid-3", "someone@example.com");
    assertContactExists("myclientid-4", "someone@example.com");
    assertContactExists("myclientid-5", "someone@example.com");
  }

  @TestOfyAndSql
  void testCreateOteEntities_setPassword() {
    OteAccountBuilder.forClientId("myclientid").setPassword("myPassword").buildAndPersist();

    assertThat(Registrar.loadByClientId("myclientid-3").get().verifyPassword("myPassword"))
        .isTrue();
  }

  @TestOfyAndSql
  void testCreateOteEntities_setCertificate() {
    OteAccountBuilder.forClientId("myclientid")
        .setCertificate(SAMPLE_CERT, new SystemClock().nowUtc())
        .buildAndPersist();

    assertThat(Registrar.loadByClientId("myclientid-3").get().getClientCertificateHash())
        .hasValue(SAMPLE_CERT_HASH);
    assertThat(Registrar.loadByClientId("myclientid-3").get().getClientCertificate())
        .hasValue(SAMPLE_CERT);
  }

  @TestOfyAndSql
  void testCreateOteEntities_setIpAllowList() {
    OteAccountBuilder.forClientId("myclientid")
        .setIpAllowList(ImmutableList.of("1.1.1.0/24"))
        .buildAndPersist();

    assertThat(Registrar.loadByClientId("myclientid-3").get().getIpAddressAllowList())
        .containsExactly(CidrAddressBlock.create("1.1.1.0/24"));
  }

  @TestOfyAndSql
  void testCreateOteEntities_invalidClientId_fails() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class, () -> OteAccountBuilder.forClientId("3blo-bio")))
        .hasMessageThat()
        .isEqualTo("Invalid registrar name: 3blo-bio");
  }

  @TestOfyAndSql
  void testCreateOteEntities_clientIdTooShort_fails() {
    assertThat(
            assertThrows(IllegalArgumentException.class, () -> OteAccountBuilder.forClientId("bl")))
        .hasMessageThat()
        .isEqualTo("Invalid registrar name: bl");
  }

  @TestOfyAndSql
  void testCreateOteEntities_clientIdTooLong_fails() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> OteAccountBuilder.forClientId("blobiotoooolong")))
        .hasMessageThat()
        .isEqualTo("Invalid registrar name: blobiotoooolong");
  }

  @TestOfyAndSql
  void testCreateOteEntities_clientIdBadCharacter_fails() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class, () -> OteAccountBuilder.forClientId("blo#bio")))
        .hasMessageThat()
        .isEqualTo("Invalid registrar name: blo#bio");
  }

  @TestOfyAndSql
  void testCreateOteEntities_registrarExists_failsWhenNotReplaceExisting() {
    persistSimpleResource(makeRegistrar1().asBuilder().setClientId("myclientid-1").build());

    OteAccountBuilder oteSetupHelper = OteAccountBuilder.forClientId("myclientid");

    assertThat(assertThrows(IllegalStateException.class, () -> oteSetupHelper.buildAndPersist()))
        .hasMessageThat()
        .contains("Found existing object(s) conflicting with OT&E objects");
  }

  @TestOfyAndSql
  void testCreateOteEntities_tldExists_failsWhenNotReplaceExisting() {
    createTld("myclientid-ga", START_DATE_SUNRISE);

    OteAccountBuilder oteSetupHelper = OteAccountBuilder.forClientId("myclientid");

    assertThat(assertThrows(IllegalStateException.class, () -> oteSetupHelper.buildAndPersist()))
        .hasMessageThat()
        .contains("Found existing object(s) conflicting with OT&E objects");
  }

  @TestOfyAndSql
  void testCreateOteEntities_entitiesExist_succeedsWhenReplaceExisting() {
    persistSimpleResource(makeRegistrar1().asBuilder().setClientId("myclientid-1").build());
    // we intentionally create the -ga TLD with the wrong state, to make sure it's overwritten.
    createTld("myclientid-ga", START_DATE_SUNRISE);

    OteAccountBuilder.forClientId("myclientid").setReplaceExisting(true).buildAndPersist();

    // Just checking a sample of the resulting entities to make sure it indeed succeeded. The full
    // entities are checked in other tests
    assertTldExists("myclientid-ga", GENERAL_AVAILABILITY, Money.zero(USD));
    assertRegistrarExists("myclientid-1", "myclientid-sunrise");
    assertRegistrarExists("myclientid-3", "myclientid-ga");
  }

  @TestOfyAndSql
  void testCreateOteEntities_doubleCreation_actuallyReplaces() {
    OteAccountBuilder.forClientId("myclientid")
        .setPassword("oldPassword")
        .addContact("email@example.com")
        .buildAndPersist();

    assertThat(Registrar.loadByClientId("myclientid-3").get().verifyPassword("oldPassword"))
        .isTrue();

    OteAccountBuilder.forClientId("myclientid")
        .setPassword("newPassword")
        .addContact("email@example.com")
        .setReplaceExisting(true)
        .buildAndPersist();

    assertThat(Registrar.loadByClientId("myclientid-3").get().verifyPassword("oldPassword"))
        .isFalse();
    assertThat(Registrar.loadByClientId("myclientid-3").get().verifyPassword("newPassword"))
        .isTrue();
  }

  @TestOfyAndSql
  void testCreateOteEntities_doubleCreation_keepsOldContacts() {
    OteAccountBuilder.forClientId("myclientid").addContact("email@example.com").buildAndPersist();

    assertContactExists("myclientid-3", "email@example.com");

    OteAccountBuilder.forClientId("myclientid")
        .addContact("other@example.com")
        .setReplaceExisting(true)
        .buildAndPersist();

    assertContactExists("myclientid-3", "other@example.com");
    assertContactExists("myclientid-3", "email@example.com");
  }

  @TestOfyAndSql
  void testCreateClientIdToTldMap_validEntries() {
    assertThat(OteAccountBuilder.createClientIdToTldMap("myclientid"))
        .containsExactly(
            "myclientid-1", "myclientid-sunrise",
            "myclientid-3", "myclientid-ga",
            "myclientid-4", "myclientid-ga",
            "myclientid-5", "myclientid-eap");
  }

  @TestOfyAndSql
  void testCreateClientIdToTldMap_invalidId() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class, () -> OteAccountBuilder.createClientIdToTldMap("a"));
    assertThat(exception).hasMessageThat().isEqualTo("Invalid registrar name: a");
  }

  @TestOfyAndSql
  void testGetBaseClientId_validOteId() {
    assertThat(OteAccountBuilder.getBaseClientId("myclientid-4")).isEqualTo("myclientid");
  }

  @TestOfyAndSql
  void testGetBaseClientId_invalidInput_malformed() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> OteAccountBuilder.getBaseClientId("myclientid")))
        .hasMessageThat()
        .isEqualTo("Invalid OT&E client ID: myclientid");
  }

  @TestOfyAndSql
  void testGetBaseClientId_invalidInput_wrongForBase() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> OteAccountBuilder.getBaseClientId("myclientid-7")))
        .hasMessageThat()
        .isEqualTo("ID myclientid-7 is not one of the OT&E client IDs for base myclientid");
  }
}
