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

package google.registry.model.registrar;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT2;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT2_HASH;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT_HASH;
import static google.registry.testing.DatabaseHelper.cloneAndSetAutoTimestamps;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newTld;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistResources;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.JPY;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import google.registry.config.RegistryConfig;
import google.registry.model.EntityTestCase;
import google.registry.model.registrar.Registrar.State;
import google.registry.model.registrar.Registrar.Type;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldType;
import google.registry.model.tld.Tlds;
import google.registry.testing.DatabaseHelper;
import google.registry.util.CidrAddressBlock;
import google.registry.util.SerializeUtils;
import java.math.BigDecimal;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Registrar}. */
class RegistrarTest extends EntityTestCase {

  private Registrar registrar;
  private RegistrarPoc abuseAdminContact;

  @BeforeEach
  void setUp() {
    createTld("tld");
    persistResource(
        newTld("xn--q9jyb4c", "MINNA")
            .asBuilder()
            .setCurrency(JPY)
            .setCreateBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.of(JPY, new BigDecimal(1300))))
            .setRestoreBillingCost(Money.of(JPY, new BigDecimal(1700)))
            .setServerStatusChangeBillingCost(Money.of(JPY, new BigDecimal(1900)))
            .setRegistryLockOrUnlockBillingCost(Money.of(JPY, new BigDecimal(2700)))
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.of(JPY, new BigDecimal(1100))))
            .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.zero(JPY)))
            .setPremiumList(null)
            .build());
    // Set up a new persisted registrar entity.
    registrar =
        cloneAndSetAutoTimestamps(
            new Registrar.Builder()
                .setRegistrarId("registrar")
                .setRegistrarName("full registrar name")
                .setType(Type.REAL)
                .setState(State.PENDING)
                .setAllowedTlds(ImmutableSet.of("xn--q9jyb4c"))
                .setWhoisServer("whois.example.com")
                .setBlockPremiumNames(true)
                .setClientCertificate(SAMPLE_CERT, fakeClock.nowUtc())
                .setIpAddressAllowList(
                    ImmutableList.of(
                        CidrAddressBlock.create("192.168.1.1/31"),
                        CidrAddressBlock.create("10.0.0.1/8")))
                .setPassword("foobar")
                .setInternationalizedAddress(
                    new RegistrarAddress.Builder()
                        .setStreet(ImmutableList.of("123 Example Boulevard"))
                        .setCity("Williamsburg")
                        .setState("NY")
                        .setZip("11211")
                        .setCountryCode("US")
                        .build())
                .setLocalizedAddress(
                    new RegistrarAddress.Builder()
                        .setStreet(ImmutableList.of("123 Example Boulevard."))
                        .setCity("Williamsburg")
                        .setState("NY")
                        .setZip("11211")
                        .setCountryCode("US")
                        .build())
                .setPhoneNumber("+1.2125551212")
                .setFaxNumber("+1.2125551213")
                .setEmailAddress("contact-us@example.com")
                .setUrl("http://www.example.com")
                .setIcannReferralEmail("foo@example.com")
                .setDriveFolderId("drive folder id")
                .setIanaIdentifier(8L)
                .setBillingAccountMap(
                    ImmutableMap.of(CurrencyUnit.USD, "abc123", CurrencyUnit.JPY, "789xyz"))
                .setPhonePasscode("01234")
                .build());
    persistResource(registrar);
    abuseAdminContact =
        new RegistrarPoc.Builder()
            .setRegistrar(registrar)
            .setName("John Abused")
            .setEmailAddress("johnabuse@example.com")
            .setVisibleInWhoisAsAdmin(true)
            .setVisibleInWhoisAsTech(false)
            .setPhoneNumber("+1.2125551213")
            .setFaxNumber("+1.2125551213")
            .setTypes(ImmutableSet.of(RegistrarPoc.Type.ABUSE, RegistrarPoc.Type.ADMIN))
            .build();
    persistResources(
        ImmutableList.of(
            abuseAdminContact,
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("John Doe")
                .setEmailAddress("johndoe@example.com")
                .setPhoneNumber("+1.2125551213")
                .setFaxNumber("+1.2125551213")
                .setTypes(ImmutableSet.of(RegistrarPoc.Type.LEGAL, RegistrarPoc.Type.MARKETING))
                .build()));
  }

  @Test
  void testPersistence() {
    assertThat(tm().transact(() -> tm().loadByKey(registrar.createVKey()))).isEqualTo(registrar);
  }

  @Test
  void testSerializable() {
    Registrar persisted = tm().transact(() -> tm().loadByKey(registrar.createVKey()));
    assertThat(SerializeUtils.serializeDeserialize(persisted)).isEqualTo(persisted);
  }

  @Test
  void testFailure_passwordNull() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> new Registrar.Builder().setPassword(null));
    assertThat(thrown).hasMessageThat().contains("Password must be 6-16 characters long.");
  }

  @Test
  void testFailure_passwordTooShort() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> new Registrar.Builder().setPassword("abcde"));
    assertThat(thrown).hasMessageThat().contains("Password must be 6-16 characters long.");
  }

  @Test
  void testFailure_passwordTooLong() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Registrar.Builder().setPassword("abcdefghijklmnopq"));
    assertThat(thrown).hasMessageThat().contains("Password must be 6-16 characters long.");
  }

  @Test
  void testSuccess_clientId_bounds() {
    registrar = registrar.asBuilder().setRegistrarId("abc").build();
    assertThat(registrar.getRegistrarId()).isEqualTo("abc");
    registrar = registrar.asBuilder().setRegistrarId("abcdefghijklmnop").build();
    assertThat(registrar.getRegistrarId()).isEqualTo("abcdefghijklmnop");
  }

  @Test
  void testFailure_clientId_tooShort() {
    assertThrows(
        IllegalArgumentException.class, () -> new Registrar.Builder().setRegistrarId("ab"));
  }

  @Test
  void testFailure_clientId_tooLong() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Registrar.Builder().setRegistrarId("abcdefghijklmnopq"));
  }

  @Test
  void testFailure_duplicateIanaId() {
    persistResource(
        registrar.asBuilder().setRegistrarId("registrar1").setIanaIdentifier(10L).build());

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                registrar.asBuilder().setRegistrarId("registrar2").setIanaIdentifier(10L).build());

    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "Rejected attempt to create a registrar with ianaId that's already in the system");
  }

  @Test
  void testSetCertificateHash_alsoSetsHash() {
    registrar = registrar.asBuilder().setClientCertificate(null, fakeClock.nowUtc()).build();
    fakeClock.advanceOneMilli();
    registrar = registrar.asBuilder().setClientCertificate(SAMPLE_CERT, fakeClock.nowUtc()).build();
    assertThat(registrar.getLastCertificateUpdateTime()).isEqualTo(fakeClock.nowUtc());
    assertThat(registrar.getClientCertificate()).hasValue(SAMPLE_CERT);
    assertThat(registrar.getClientCertificateHash()).hasValue(SAMPLE_CERT_HASH);
  }

  @Test
  void testDeleteCertificateHash_alsoDeletesHash() {
    assertThat(registrar.getClientCertificateHash()).isPresent();
    fakeClock.advanceOneMilli();
    registrar = registrar.asBuilder().setClientCertificate(null, fakeClock.nowUtc()).build();
    assertThat(registrar.getLastCertificateUpdateTime()).isEqualTo(fakeClock.nowUtc());
    assertThat(registrar.getClientCertificate()).isEmpty();
    assertThat(registrar.getClientCertificateHash()).isEmpty();
  }

  @Test
  void testSetFailoverCertificateHash_alsoSetsHash() {
    fakeClock.advanceOneMilli();
    registrar =
        registrar
            .asBuilder()
            .setFailoverClientCertificate(SAMPLE_CERT2, fakeClock.nowUtc())
            .build();
    assertThat(registrar.getLastCertificateUpdateTime()).isEqualTo(fakeClock.nowUtc());
    assertThat(registrar.getFailoverClientCertificate()).hasValue(SAMPLE_CERT2);
    assertThat(registrar.getFailoverClientCertificateHash()).hasValue(SAMPLE_CERT2_HASH);
  }

  @Test
  void testDeleteFailoverCertificateHash_alsoDeletesHash() {
    registrar =
        registrar.asBuilder().setFailoverClientCertificate(SAMPLE_CERT, fakeClock.nowUtc()).build();
    assertThat(registrar.getFailoverClientCertificateHash()).isPresent();
    fakeClock.advanceOneMilli();
    registrar =
        registrar.asBuilder().setFailoverClientCertificate(null, fakeClock.nowUtc()).build();
    assertThat(registrar.getLastCertificateUpdateTime()).isEqualTo(fakeClock.nowUtc());
    assertThat(registrar.getFailoverClientCertificate()).isEmpty();
    assertThat(registrar.getFailoverClientCertificateHash()).isEmpty();
  }

  @Test
  void testSuccess_clearingIanaId() {
    registrar
        .asBuilder()
        .setType(Type.TEST)
        .setIanaIdentifier(null)
        .build();
  }

  @Test
  void testSuccess_clearingBillingAccountMapAndAllowedTlds() {
    registrar =
        registrar.asBuilder().setAllowedTlds(ImmutableSet.of()).setBillingAccountMap(null).build();
    assertThat(registrar.getAllowedTlds()).isEmpty();
    assertThat(registrar.getBillingAccountMap()).isEmpty();
  }

  @Test
  void testSuccess_ianaIdForInternal() {
    registrar.asBuilder().setType(Type.INTERNAL).setIanaIdentifier(9998L).build();
    registrar.asBuilder().setType(Type.INTERNAL).setIanaIdentifier(9999L).build();
  }

  @Test
  void testSuccess_ianaIdForPdt() {
    registrar.asBuilder().setType(Type.PDT).setIanaIdentifier(9995L).build();
    registrar.asBuilder().setType(Type.PDT).setIanaIdentifier(9996L).build();
  }

  @Test
  void testSuccess_ianaIdForExternalMonitoring() {
    registrar.asBuilder().setType(Type.EXTERNAL_MONITORING).setIanaIdentifier(9997L).build();
  }

  @Test
  void testSuccess_emptyContactTypesAllowed() {
    persistResource(
        new RegistrarPoc.Builder()
            .setRegistrar(registrar)
            .setName("John Abussy")
            .setEmailAddress("johnabussy@example.com")
            .setPhoneNumber("+1.2125551213")
            .setFaxNumber("+1.2125551213")
            // No setTypes(...)
            .build());
    for (RegistrarPoc rc : registrar.getContacts()) {
      rc.toJsonMap();
    }
  }

  @Test
  void testSuccess_getContactsByType() {
    RegistrarPoc newTechContact =
        persistResource(
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("Jake Tech")
                .setEmailAddress("jaketech@example.com")
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(true)
                .setPhoneNumber("+1.2125551213")
                .setFaxNumber("+1.2125551213")
                .setTypes(ImmutableSet.of(RegistrarPoc.Type.TECH))
                .build());
    RegistrarPoc newTechAbuseContact =
        persistResource(
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("Jim Tech-Abuse")
                .setEmailAddress("jimtechAbuse@example.com")
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(true)
                .setPhoneNumber("+1.2125551213")
                .setFaxNumber("+1.2125551213")
                .setTypes(ImmutableSet.of(RegistrarPoc.Type.TECH, RegistrarPoc.Type.ABUSE))
                .build());
    abuseAdminContact = DatabaseHelper.loadByKey(abuseAdminContact.createVKey());
    ImmutableSortedSet<RegistrarPoc> techContacts =
        registrar.getContactsOfType(RegistrarPoc.Type.TECH);
    assertThat(techContacts).containsExactly(newTechContact, newTechAbuseContact).inOrder();
    ImmutableSortedSet<RegistrarPoc> abuseContacts =
        registrar.getContactsOfType(RegistrarPoc.Type.ABUSE);
    assertThat(abuseContacts).containsExactly(newTechAbuseContact, abuseAdminContact).inOrder();
  }

  @Test
  void testFailure_missingRegistrarType() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Registrar.Builder().setRegistrarName("blah").build());
    assertThat(thrown).hasMessageThat().contains("Registrar type cannot be null");
  }

  @Test
  void testFailure_missingRegistrarName() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new Registrar.Builder()
                    .setRegistrarId("blahid")
                    .setType(Registrar.Type.TEST)
                    .build());
    assertThat(thrown).hasMessageThat().contains("Registrar name cannot be null");
  }

  @Test
  void testFailure_missingAddress() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new Registrar.Builder()
                    .setRegistrarId("blahid")
                    .setType(Registrar.Type.TEST)
                    .setRegistrarName("Blah Co")
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .contains("Must specify at least one of localized or internationalized address");
  }

  @Test
  void testFailure_badIanaIdForInternal() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Registrar.Builder().setType(Type.INTERNAL).setIanaIdentifier(8L).build());
  }

  @Test
  void testFailure_badIanaIdForPdt() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Registrar.Builder().setType(Type.PDT).setIanaIdentifier(8L).build());
  }

  @Test
  void testFailure_badIanaIdForExternalMonitoring() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            registrar.asBuilder().setType(Type.EXTERNAL_MONITORING).setIanaIdentifier(8L).build());
  }

  @Test
  void testFailure_missingIanaIdForReal() {
    assertThrows(
        IllegalArgumentException.class, () -> new Registrar.Builder().setType(Type.REAL).build());
  }

  @Test
  void testFailure_missingIanaIdForInternal() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Registrar.Builder().setType(Type.INTERNAL).build());
  }

  @Test
  void testFailure_missingIanaIdForPdt() {
    assertThrows(
        IllegalArgumentException.class, () -> new Registrar.Builder().setType(Type.PDT).build());
  }

  @Test
  void testFailure_missingIanaIdForExternalMonitoring() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Registrar.Builder().setType(Type.EXTERNAL_MONITORING).build());
  }

  @Test
  void testFailure_phonePasscodeTooShort() {
    assertThrows(
        IllegalArgumentException.class, () -> new Registrar.Builder().setPhonePasscode("0123"));
  }

  @Test
  void testFailure_phonePasscodeTooLong() {
    assertThrows(
        IllegalArgumentException.class, () -> new Registrar.Builder().setPhonePasscode("012345"));
  }

  @Test
  void testFailure_phonePasscodeInvalidCharacters() {
    assertThrows(
        IllegalArgumentException.class, () -> new Registrar.Builder().setPhonePasscode("code1"));
  }

  @Test
  void testSuccess_getLastExpiringCertNotificationSentDate_returnsInitialValue() {
    assertThat(registrar.getLastExpiringCertNotificationSentDate()).isEqualTo(START_OF_TIME);
  }

  @Test
  void testSuccess_getLastExpiringFailoverCertNotificationSentDate_returnsInitialValue() {
    assertThat(registrar.getLastExpiringFailoverCertNotificationSentDate())
        .isEqualTo(START_OF_TIME);
  }

  @Test
  void testSuccess_setLastExpiringCertNotificationSentDate() {
    assertThat(
            registrar
                .asBuilder()
                .setLastExpiringCertNotificationSentDate(fakeClock.nowUtc())
                .build()
                .getLastExpiringCertNotificationSentDate())
        .isEqualTo(fakeClock.nowUtc());
  }

  @Test
  void testFailure_setLastExpiringCertNotificationSentDate_nullDate() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Registrar.Builder().setLastExpiringCertNotificationSentDate(null).build());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Registrar lastExpiringCertNotificationSentDate cannot be null");
  }

  @Test
  void testSuccess_setLastExpiringFailoverCertNotificationSentDate() {
    assertThat(
            registrar
                .asBuilder()
                .setLastExpiringFailoverCertNotificationSentDate(fakeClock.nowUtc())
                .build()
                .getLastExpiringFailoverCertNotificationSentDate())
        .isEqualTo(fakeClock.nowUtc());
  }

  @Test
  void testSuccess_setLastPocVerificationDate() {
    assertThat(
            registrar
                .asBuilder()
                .setLastPocVerificationDate(fakeClock.nowUtc())
                .build()
                .getLastPocVerificationDate())
        .isEqualTo(fakeClock.nowUtc());
  }

  @Test
  void testFailure_setLastPocVerificationDate_nullDate() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Registrar.Builder().setLastPocVerificationDate(null).build());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Registrar lastPocVerificationDate cannot be null");
  }

  @Test
  void testFailure_setLastExpiringFailoverCertNotificationSentDate_nullDate() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new Registrar.Builder()
                    .setLastExpiringFailoverCertNotificationSentDate(null)
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Registrar lastExpiringFailoverCertNotificationSentDate cannot be null");
  }

  @Test
  void testSuccess_setAllowedTlds() {
    assertThat(
            registrar
                .asBuilder()
                .setAllowedTlds(ImmutableSet.of("xn--q9jyb4c"))
                .build()
                .getAllowedTlds())
        .containsExactly("xn--q9jyb4c");
  }

  @Test
  void testSuccess_setAllowedTldsUncached() {
    assertThat(
            tm().transact(
                    () -> {
                      return registrar
                          .asBuilder()
                          .setAllowedTldsUncached(ImmutableSet.of("xn--q9jyb4c"))
                          .build()
                          .getAllowedTlds();
                    }))
        .containsExactly("xn--q9jyb4c");
  }

  @Test
  void testFailure_setAllowedTlds_nonexistentTld() {
    assertThrows(
        IllegalArgumentException.class,
        () -> registrar.asBuilder().setAllowedTlds(ImmutableSet.of("bad")));
  }

  @Test
  void testFailure_setAllowedTldsUncached_nonexistentTld() {
    tm().transact(
            () -> {
              assertThrows(
                  IllegalArgumentException.class,
                  () -> registrar.asBuilder().setAllowedTldsUncached(ImmutableSet.of("bad")));
            });
  }

  @Test
  void testFailure_driveFolderId_asFullUrl() {
    String driveFolderId =
        "https://drive.google.com/drive/folders/1j3v7RZkU25DjbTx2-Q93H04zKOBau89M";
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> registrar.asBuilder().setDriveFolderId(driveFolderId));
    assertThat(thrown).hasMessageThat().isEqualTo("Drive folder ID must not be a full URL");
  }

  @Test
  void testFailure_nullEmail() {
    NullPointerException thrown =
        assertThrows(NullPointerException.class, () -> registrar.asBuilder().setEmailAddress(null));
    assertThat(thrown).hasMessageThat().isEqualTo("Provided email was null");
  }

  @Test
  void testFailure_invalidEmail() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> registrar.asBuilder().setEmailAddress("lolcat"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Provided email lolcat is not a valid email address");
  }

  @Test
  void testFailure_emptyEmail() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> registrar.asBuilder().setEmailAddress(""));
    assertThat(thrown).hasMessageThat().isEqualTo("Provided email  is not a valid email address");
  }

  @Test
  void testFailure_nullIcannReferralEmail() {
    NullPointerException thrown =
        assertThrows(
            NullPointerException.class, () -> registrar.asBuilder().setIcannReferralEmail(null));
    assertThat(thrown).hasMessageThat().isEqualTo("Provided email was null");
  }

  @Test
  void testFailure_invalidIcannReferralEmail() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> registrar.asBuilder().setIcannReferralEmail("lolcat"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Provided email lolcat is not a valid email address");
  }

  @Test
  void testFailure_emptyIcannReferralEmail() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> registrar.asBuilder().setEmailAddress(""));
    assertThat(thrown).hasMessageThat().isEqualTo("Provided email  is not a valid email address");
  }

  @Test
  void testSuccess_setAllowedTldsUncached_newTldNotInCache() {
    int origSingletonCacheRefreshSeconds =
        RegistryConfig.CONFIG_SETTINGS.get().caching.singletonCacheRefreshSeconds;
    // Sanity check for Gradle-based open-source build.
    checkState(
        origSingletonCacheRefreshSeconds == 0, "singletonCacheRefreshSeconds expected to be 0.");
    try {
      // Cache duration in tests is 0. To make sure the data isn't in the cache we have to set it
      // to a higher value and reset the cache.
      RegistryConfig.CONFIG_SETTINGS.get().caching.singletonCacheRefreshSeconds = 600;
      Tlds.resetCache();
      // Make sure the TLD we want to create doesn't exist yet.
      // This is also important because getTlds fills out the cache when used.
      assertThat(Tlds.getTlds()).doesNotContain("newtld");
      // We can't use createTld here because it fails when the cache is used.
      persistResource(newTld("newtld", "NEWTLD"));
      // Make sure we set up the cache correctly, so the newly created TLD isn't in the cache
      assertThat(Tlds.getTlds()).doesNotContain("newtld");

      // Test that the uncached version works
      assertThat(
              tm().transact(
                      () -> {
                        return registrar
                            .asBuilder()
                            .setAllowedTldsUncached(ImmutableSet.of("newtld"))
                            .build()
                            .getAllowedTlds();
                      }))
          .containsExactly("newtld");

      // Test that the "regular" cached version fails. If this doesn't throw - then we changed how
      // the cached version works:
      // - either we switched to a different cache type/duration, and we haven't actually set up
      //   that cache in the test
      // - or we stopped using the cache entirely and we should rethink if the Uncached version is
      //   still needed
      assertThrows(
          IllegalArgumentException.class,
          () -> registrar.asBuilder().setAllowedTlds(ImmutableSet.of("newtld")));

      // Make sure the cache hasn't expired during the test and "newtld" is still not in the cached
      // TLDs
      assertThat(Tlds.getTlds()).doesNotContain("newtld");
    } finally {
      RegistryConfig.CONFIG_SETTINGS.get().caching.singletonCacheRefreshSeconds =
          origSingletonCacheRefreshSeconds;
      Tlds.resetCache();
    }
  }

  @Test
  void testFailure_loadByClientId_clientIdIsNull() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> Registrar.loadByRegistrarId(null));
    assertThat(thrown).hasMessageThat().contains("registrarId must be specified");
  }

  @Test
  void testFailure_loadByClientId_clientIdIsEmpty() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> Registrar.loadByRegistrarId(""));
    assertThat(thrown).hasMessageThat().contains("registrarId must be specified");
  }

  @Test
  void testFailure_loadByClientIdCached_clientIdIsNull() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> Registrar.loadByRegistrarIdCached(null));
    assertThat(thrown).hasMessageThat().contains("registrarId must be specified");
  }

  @Test
  void testFailure_loadByClientIdCached_clientIdIsEmpty() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> Registrar.loadByRegistrarIdCached(""));
    assertThat(thrown).hasMessageThat().contains("registrarId must be specified");
  }

  @Test
  void testFailure_missingCurrenciesFromBillingMap() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                registrar
                    .asBuilder()
                    .setBillingAccountMap(null)
                    .setAllowedTlds(ImmutableSet.of("tld", "xn--q9jyb4c"))
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .contains("their currency is missing from the billing account map: [tld, xn--q9jyb4c]");
  }

  @Test
  void testFailure_missingCurrencyFromBillingMap() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                registrar
                    .asBuilder()
                    .setBillingAccountMap(ImmutableMap.of(USD, "abc123"))
                    .setAllowedTlds(ImmutableSet.of("tld", "xn--q9jyb4c"))
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .contains("their currency is missing from the billing account map: [xn--q9jyb4c]");
  }

  @Test
  void testSuccess_nonRealTldDoesntNeedEntryInBillingMap() {
    persistResource(Tld.get("xn--q9jyb4c").asBuilder().setTldType(TldType.TEST).build());
    // xn--q9jyb4c bills in JPY and we don't have a JPY entry in this billing account map, but it
    // should succeed without throwing an error because xn--q9jyb4c is set to be a TEST TLD.
    registrar
        .asBuilder()
        .setBillingAccountMap(ImmutableMap.of(USD, "abc123"))
        .setAllowedTlds(ImmutableSet.of("tld", "xn--q9jyb4c"))
        .build();
  }
}
