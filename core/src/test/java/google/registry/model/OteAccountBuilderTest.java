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
import static google.registry.model.console.User.IAP_SECURED_WEB_APP_USER_ROLE;
import static google.registry.model.tld.Tld.TldState.GENERAL_AVAILABILITY;
import static google.registry.model.tld.Tld.TldState.START_DATE_SUNRISE;
import static google.registry.persistence.transaction.JpaTransactionManagerExtension.makeRegistrar1;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT_HASH;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByKeyIfPresent;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.cloud.tasks.v2.HttpMethod;
import com.google.common.collect.ImmutableList;
import google.registry.batch.CloudTasksUtils;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldState;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.CloudTasksHelper.TaskMatcher;
import google.registry.tools.IamClient;
import google.registry.util.CidrAddressBlock;
import google.registry.util.SystemClock;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public final class OteAccountBuilderTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private final CloudTasksHelper cloudTasksHelper = new CloudTasksHelper();
  private final IamClient iamClient = mock(IamClient.class);

  @Test
  void testGetRegistrarToTldMap() {
    assertThat(OteAccountBuilder.forRegistrarId("myclientid").getRegistrarIdToTldMap())
        .containsExactly(
            "myclientid-1", "myclientid-sunrise",
            "myclientid-3", "myclientid-ga",
            "myclientid-4", "myclientid-ga",
            "myclientid-5", "myclientid-eap");
  }

  @BeforeEach
  void beforeEach() {
    persistPremiumList("default_sandbox_list", USD, "sandbox,USD 1000");
  }

  private static void assertTldExists(String tld, TldState tldState, Money eapFee) {
    Tld registry = Tld.get(tld);
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

  private static void assertRegistrarExists(String registrarId, String tld) {
    Registrar registrar = Registrar.loadByRegistrarId(registrarId).orElse(null);
    assertThat(registrar).isNotNull();
    assertThat(registrar.getType()).isEqualTo(Registrar.Type.OTE);
    assertThat(registrar.getState()).isEqualTo(Registrar.State.ACTIVE);
    assertThat(registrar.getAllowedTlds()).containsExactly(tld);
  }

  public static void verifyUser(String registrarId, String email) {
    Optional<User> maybeUser = loadByKeyIfPresent(VKey.create(User.class, email));
    assertThat(maybeUser).isPresent();
    assertThat(maybeUser.get().getUserRoles().getRegistrarRoles().get(registrarId))
        .isEqualTo(RegistrarRole.ACCOUNT_MANAGER);
  }

  public static void verifyIapPermission(
      @Nullable String emailAddress,
      Optional<String> maybeGroupEmailAddress,
      CloudTasksHelper cloudTasksHelper,
      IamClient iamClient) {
    if (emailAddress == null) {
      cloudTasksHelper.assertNoTasksEnqueued("console-user-group-update");
      verifyNoInteractions(iamClient);
    } else {
      String groupEmailAddress = maybeGroupEmailAddress.orElse(null);
      if (groupEmailAddress == null) {
        cloudTasksHelper.assertNoTasksEnqueued("console-user-group-update");
        verify(iamClient).addBinding(emailAddress, IAP_SECURED_WEB_APP_USER_ROLE);
      } else {
        cloudTasksHelper.assertTasksEnqueued(
            "console-user-group-update",
            new TaskMatcher()
                .service("TOOLS")
                .method(HttpMethod.POST)
                .path("/_dr/admin/updateUserGroup")
                .param("userEmailAddress", emailAddress)
                .param("groupEmailAddress", groupEmailAddress)
                .param("groupUpdateMode", "ADD"));
        verifyNoInteractions(iamClient);
      }
    }
  }

  @Test
  void testUpdateUserGroup() {
    CloudTasksUtils cloudTasksUtils = cloudTasksHelper.getTestCloudTasksUtils();
    OteAccountBuilder.forRegistrarId("myclientid")
        .addUser("email@example.com")
        .grantIapPermission(Optional.of("console@example.com"), cloudTasksUtils, iamClient);
    verifyIapPermission(
        "email@example.com", Optional.of("console@example.com"), cloudTasksHelper, iamClient);
  }

  @Test
  void testGrantIndividualPermission() {
    CloudTasksUtils cloudTasksUtils = cloudTasksHelper.getTestCloudTasksUtils();
    OteAccountBuilder.forRegistrarId("myclientid")
        .addUser("email@example.com")
        .grantIapPermission(Optional.empty(), cloudTasksUtils, iamClient);
    verifyIapPermission("email@example.com", Optional.empty(), cloudTasksHelper, iamClient);
  }

  @Test
  void testCreateOteEntities_success() {
    OteAccountBuilder.forRegistrarId("myclientid").addUser("email@example.com").buildAndPersist();

    assertTldExists("myclientid-sunrise", START_DATE_SUNRISE, Money.zero(USD));
    assertTldExists("myclientid-ga", GENERAL_AVAILABILITY, Money.zero(USD));
    assertTldExists("myclientid-eap", GENERAL_AVAILABILITY, Money.of(USD, 100));
    assertRegistrarExists("myclientid-1", "myclientid-sunrise");
    assertRegistrarExists("myclientid-3", "myclientid-ga");
    assertRegistrarExists("myclientid-4", "myclientid-ga");
    assertRegistrarExists("myclientid-5", "myclientid-eap");
    verifyUser("myclientid-1", "email@example.com");
    verifyUser("myclientid-3", "email@example.com");
    verifyUser("myclientid-4", "email@example.com");
    verifyUser("myclientid-5", "email@example.com");
  }

  @Test
  void testCreateOteEntities_multipleContacts_success() {
    OteAccountBuilder.forRegistrarId("myclientid")
        .addUser("email@example.com")
        .addUser("other@example.com")
        .addUser("someone@example.com")
        .buildAndPersist();

    assertTldExists("myclientid-sunrise", START_DATE_SUNRISE, Money.zero(USD));
    assertTldExists("myclientid-ga", GENERAL_AVAILABILITY, Money.zero(USD));
    assertTldExists("myclientid-eap", GENERAL_AVAILABILITY, Money.of(USD, 100));
    assertRegistrarExists("myclientid-1", "myclientid-sunrise");
    assertRegistrarExists("myclientid-3", "myclientid-ga");
    assertRegistrarExists("myclientid-4", "myclientid-ga");
    assertRegistrarExists("myclientid-5", "myclientid-eap");
    verifyUser("myclientid-1", "email@example.com");
    verifyUser("myclientid-3", "email@example.com");
    verifyUser("myclientid-4", "email@example.com");
    verifyUser("myclientid-5", "email@example.com");
    verifyUser("myclientid-1", "other@example.com");
    verifyUser("myclientid-3", "other@example.com");
    verifyUser("myclientid-4", "other@example.com");
    verifyUser("myclientid-5", "other@example.com");
    verifyUser("myclientid-1", "someone@example.com");
    verifyUser("myclientid-3", "someone@example.com");
    verifyUser("myclientid-4", "someone@example.com");
    verifyUser("myclientid-5", "someone@example.com");
  }

  @Test
  void testCreateOteEntities_setPassword() {
    OteAccountBuilder.forRegistrarId("myclientid").setPassword("myPassword").buildAndPersist();

    assertThat(Registrar.loadByRegistrarId("myclientid-3").get().verifyPassword("myPassword"))
        .isTrue();
  }

  @Test
  void testCreateOteEntities_setCertificate() {
    OteAccountBuilder.forRegistrarId("myclientid")
        .setCertificate(SAMPLE_CERT, new SystemClock().nowUtc())
        .buildAndPersist();

    assertThat(Registrar.loadByRegistrarId("myclientid-3").get().getClientCertificateHash())
        .hasValue(SAMPLE_CERT_HASH);
    assertThat(Registrar.loadByRegistrarId("myclientid-3").get().getClientCertificate())
        .hasValue(SAMPLE_CERT);
  }

  @Test
  void testCreateOteEntities_setIpAllowList() {
    OteAccountBuilder.forRegistrarId("myclientid")
        .setIpAllowList(ImmutableList.of("1.1.1.0/24"))
        .buildAndPersist();

    assertThat(Registrar.loadByRegistrarId("myclientid-3").get().getIpAddressAllowList())
        .containsExactly(CidrAddressBlock.create("1.1.1.0/24"));
  }

  @Test
  void testCreateOteEntities_invalidRegistrarId_fails() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class, () -> OteAccountBuilder.forRegistrarId("3blo-bio")))
        .hasMessageThat()
        .isEqualTo("Invalid registrar name: 3blo-bio");
  }

  @Test
  void testCreateOteEntities_clientIdTooShort_fails() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class, () -> OteAccountBuilder.forRegistrarId("bl")))
        .hasMessageThat()
        .isEqualTo("Invalid registrar name: bl");
  }

  @Test
  void testCreateOteEntities_clientIdTooLong_fails() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> OteAccountBuilder.forRegistrarId("blobiotoooolong")))
        .hasMessageThat()
        .isEqualTo("Invalid registrar name: blobiotoooolong");
  }

  @Test
  void testCreateOteEntities_clientIdBadCharacter_fails() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class, () -> OteAccountBuilder.forRegistrarId("blo#bio")))
        .hasMessageThat()
        .isEqualTo("Invalid registrar name: blo#bio");
  }

  @Test
  void testCreateOteEntities_registrarExists_failsWhenNotReplaceExisting() {
    persistResource(makeRegistrar1().asBuilder().setRegistrarId("myclientid-1").build());

    OteAccountBuilder oteSetupHelper = OteAccountBuilder.forRegistrarId("myclientid");

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, oteSetupHelper::buildAndPersist);
    assertThat(thrown)
        .hasMessageThat()
        .contains("Found existing object(s) conflicting with OT&E objects");
  }

  @Test
  void testCreateOteEntities_tldExists_failsWhenNotReplaceExisting() {
    createTld("myclientid-ga", START_DATE_SUNRISE);

    OteAccountBuilder oteSetupHelper = OteAccountBuilder.forRegistrarId("myclientid");

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, oteSetupHelper::buildAndPersist);
    assertThat(thrown)
        .hasMessageThat()
        .contains("Found existing object(s) conflicting with OT&E objects");
  }

  @Test
  void testCreateOteEntities_entitiesExist_succeedsWhenReplaceExisting() {
    persistResource(makeRegistrar1().asBuilder().setRegistrarId("myclientid-1").build());
    // we intentionally create the -ga TLD with the wrong state, to make sure it's overwritten.
    createTld("myclientid-ga", START_DATE_SUNRISE);

    OteAccountBuilder.forRegistrarId("myclientid").setReplaceExisting(true).buildAndPersist();

    // Just checking a sample of the resulting entities to make sure it indeed succeeded. The full
    // entities are checked in other tests
    assertTldExists("myclientid-ga", GENERAL_AVAILABILITY, Money.zero(USD));
    assertRegistrarExists("myclientid-1", "myclientid-sunrise");
    assertRegistrarExists("myclientid-3", "myclientid-ga");
  }

  @Test
  void testCreateOteEntities_doubleCreation_actuallyReplaces() {
    OteAccountBuilder.forRegistrarId("myclientid")
        .setPassword("oldPassword")
        .addUser("email@example.com")
        .buildAndPersist();

    assertThat(Registrar.loadByRegistrarId("myclientid-3").get().verifyPassword("oldPassword"))
        .isTrue();

    OteAccountBuilder.forRegistrarId("myclientid")
        .setPassword("newPassword")
        .addUser("email@example.com")
        .setReplaceExisting(true)
        .buildAndPersist();

    assertThat(Registrar.loadByRegistrarId("myclientid-3").get().verifyPassword("oldPassword"))
        .isFalse();
    assertThat(Registrar.loadByRegistrarId("myclientid-3").get().verifyPassword("newPassword"))
        .isTrue();
  }

  @Test
  void testCreateOteEntities_doubleCreation_keepsOldContacts() {
    OteAccountBuilder.forRegistrarId("myclientid").addUser("email@example.com").buildAndPersist();

    verifyUser("myclientid-3", "email@example.com");

    OteAccountBuilder.forRegistrarId("myclientid")
        .addUser("other@example.com")
        .setReplaceExisting(true)
        .buildAndPersist();

    verifyUser("myclientid-3", "other@example.com");
    verifyUser("myclientid-3", "email@example.com");
  }

  @Test
  void testCreateRegistrarIdToTldMap_validEntries() {
    assertThat(OteAccountBuilder.createRegistrarIdToTldMap("myclientid"))
        .containsExactly(
            "myclientid-1", "myclientid-sunrise",
            "myclientid-3", "myclientid-ga",
            "myclientid-4", "myclientid-ga",
            "myclientid-5", "myclientid-eap");
  }

  @Test
  void testCreateRegistrarIdToTldMap_invalidId() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class, () -> OteAccountBuilder.createRegistrarIdToTldMap("a"));
    assertThat(exception).hasMessageThat().isEqualTo("Invalid registrar name: a");
  }

  @Test
  void testGetBaseRegistrarId_validOteId() {
    assertThat(OteAccountBuilder.getBaseRegistrarId("myclientid-4")).isEqualTo("myclientid");
  }

  @Test
  void testGetBaseRegistrarId_invalidInput_malformed() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> OteAccountBuilder.getBaseRegistrarId("myclientid")))
        .hasMessageThat()
        .isEqualTo("Invalid OT&E registrar ID: myclientid");
  }

  @Test
  void testGetBaseRegistrarId_invalidInput_wrongForBase() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> OteAccountBuilder.getBaseRegistrarId("myclientid-7")))
        .hasMessageThat()
        .isEqualTo("ID myclientid-7 is not one of the OT&E registrar IDs for base myclientid");
  }
}
