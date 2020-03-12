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
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT2;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT2_HASH;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT_HASH;
import static google.registry.testing.DatastoreHelper.cloneAndSetAutoTimestamps;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newRegistry;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DatastoreHelper.persistSimpleResource;
import static google.registry.testing.DatastoreHelper.persistSimpleResources;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.googlecode.objectify.Key;
import google.registry.config.RegistryConfig;
import google.registry.model.EntityTestCase;
import google.registry.model.common.EntityGroupRoot;
import google.registry.model.registrar.Registrar.State;
import google.registry.model.registrar.Registrar.Type;
import google.registry.model.registry.Registries;
import google.registry.util.CidrAddressBlock;
import org.joda.money.CurrencyUnit;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link Registrar}. */
public class RegistrarTest extends EntityTestCase {
  private Registrar registrar;
  private RegistrarContact abuseAdminContact;

  @Before
  public void setUp() {
    createTld("xn--q9jyb4c");
    // Set up a new persisted registrar entity.
    registrar =
        cloneAndSetAutoTimestamps(
            new Registrar.Builder()
                .setClientId("registrar")
                .setRegistrarName("full registrar name")
                .setType(Type.REAL)
                .setState(State.PENDING)
                .setAllowedTlds(ImmutableSet.of("xn--q9jyb4c"))
                .setWhoisServer("whois.example.com")
                .setBlockPremiumNames(true)
                .setClientCertificate(SAMPLE_CERT, fakeClock.nowUtc())
                .setIpAddressWhitelist(
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
                .setBillingIdentifier(5325L)
                .setBillingAccountMap(
                    ImmutableMap.of(CurrencyUnit.USD, "abc123", CurrencyUnit.JPY, "789xyz"))
                .setPhonePasscode("01234")
                .build());
    persistResource(registrar);
    abuseAdminContact =
        new RegistrarContact.Builder()
            .setParent(registrar)
            .setName("John Abused")
            .setEmailAddress("johnabuse@example.com")
            .setVisibleInWhoisAsAdmin(true)
            .setVisibleInWhoisAsTech(false)
            .setPhoneNumber("+1.2125551213")
            .setFaxNumber("+1.2125551213")
            .setTypes(ImmutableSet.of(RegistrarContact.Type.ABUSE, RegistrarContact.Type.ADMIN))
            .build();
    persistSimpleResources(
        ImmutableList.of(
            abuseAdminContact,
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("John Doe")
                .setEmailAddress("johndoe@example.com")
                .setPhoneNumber("+1.2125551213")
                .setFaxNumber("+1.2125551213")
                .setTypes(
                    ImmutableSet.of(RegistrarContact.Type.LEGAL, RegistrarContact.Type.MARKETING))
                .build()));
  }

  @Test
  public void testPersistence() {
    assertThat(registrar)
        .isEqualTo(
            ofy()
                .load()
                .type(Registrar.class)
                .parent(EntityGroupRoot.getCrossTldKey())
                .id(registrar.getClientId())
                .now());
  }

  @Test
  public void testIndexing() throws Exception {
    verifyIndexing(registrar, "registrarName", "ianaIdentifier");
  }

  @Test
  public void testFailure_passwordNull() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> new Registrar.Builder().setPassword(null));
    assertThat(thrown).hasMessageThat().contains("Password must be 6-16 characters long.");
  }

  @Test
  public void testFailure_passwordTooShort() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> new Registrar.Builder().setPassword("abcde"));
    assertThat(thrown).hasMessageThat().contains("Password must be 6-16 characters long.");
  }

  @Test
  public void testFailure_passwordTooLong() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Registrar.Builder().setPassword("abcdefghijklmnopq"));
    assertThat(thrown).hasMessageThat().contains("Password must be 6-16 characters long.");
  }

  @Test
  public void testSuccess_clientId_bounds() {
    registrar = registrar.asBuilder().setClientId("abc").build();
    assertThat(registrar.getClientId()).isEqualTo("abc");
    registrar = registrar.asBuilder().setClientId("abcdefghijklmnop").build();
    assertThat(registrar.getClientId()).isEqualTo("abcdefghijklmnop");
  }

  @Test
  public void testFailure_clientId_tooShort() {
    assertThrows(IllegalArgumentException.class, () -> new Registrar.Builder().setClientId("ab"));
  }

  @Test
  public void testFailure_clientId_tooLong() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Registrar.Builder().setClientId("abcdefghijklmnopq"));
  }

  @Test
  public void testSetCertificateHash_alsoSetsHash() {
    registrar = registrar.asBuilder().setClientCertificate(null, fakeClock.nowUtc()).build();
    fakeClock.advanceOneMilli();
    registrar = registrar.asBuilder().setClientCertificate(SAMPLE_CERT, fakeClock.nowUtc()).build();
    assertThat(registrar.getLastCertificateUpdateTime()).isEqualTo(fakeClock.nowUtc());
    assertThat(registrar.getClientCertificate()).isEqualTo(SAMPLE_CERT);
    assertThat(registrar.getClientCertificateHash()).isEqualTo(SAMPLE_CERT_HASH);
  }

  @Test
  public void testDeleteCertificateHash_alsoDeletesHash() {
    assertThat(registrar.getClientCertificateHash()).isNotNull();
    fakeClock.advanceOneMilli();
    registrar = registrar.asBuilder().setClientCertificate(null, fakeClock.nowUtc()).build();
    assertThat(registrar.getLastCertificateUpdateTime()).isEqualTo(fakeClock.nowUtc());
    assertThat(registrar.getClientCertificate()).isNull();
    assertThat(registrar.getClientCertificateHash()).isNull();
  }

  @Test
  public void testSetFailoverCertificateHash_alsoSetsHash() {
    fakeClock.advanceOneMilli();
    registrar =
        registrar
            .asBuilder()
            .setFailoverClientCertificate(SAMPLE_CERT2, fakeClock.nowUtc())
            .build();
    assertThat(registrar.getLastCertificateUpdateTime()).isEqualTo(fakeClock.nowUtc());
    assertThat(registrar.getFailoverClientCertificate()).isEqualTo(SAMPLE_CERT2);
    assertThat(registrar.getFailoverClientCertificateHash()).isEqualTo(SAMPLE_CERT2_HASH);
  }

  @Test
  public void testDeleteFailoverCertificateHash_alsoDeletesHash() {
    registrar =
        registrar.asBuilder().setFailoverClientCertificate(SAMPLE_CERT, fakeClock.nowUtc()).build();
    assertThat(registrar.getFailoverClientCertificateHash()).isNotNull();
    fakeClock.advanceOneMilli();
    registrar =
        registrar.asBuilder().setFailoverClientCertificate(null, fakeClock.nowUtc()).build();
    assertThat(registrar.getLastCertificateUpdateTime()).isEqualTo(fakeClock.nowUtc());
    assertThat(registrar.getFailoverClientCertificate()).isNull();
    assertThat(registrar.getFailoverClientCertificateHash()).isNull();
  }

  @Test
  public void testSuccess_clearingIanaAndBillingIds() {
    registrar
        .asBuilder()
        .setType(Type.TEST)
        .setIanaIdentifier(null)
        .setBillingIdentifier(null)
        .build();
  }

  @Test
  public void testSuccess_clearingBillingAccountMap() {
    registrar = registrar.asBuilder().setBillingAccountMap(null).build();
    assertThat(registrar.getBillingAccountMap()).isEmpty();
  }

  @Test
  public void testSuccess_ianaIdForInternal() {
    registrar.asBuilder().setType(Type.INTERNAL).setIanaIdentifier(9998L).build();
    registrar.asBuilder().setType(Type.INTERNAL).setIanaIdentifier(9999L).build();
  }

  @Test
  public void testSuccess_ianaIdForPdt() {
    registrar.asBuilder().setType(Type.PDT).setIanaIdentifier(9995L).build();
    registrar.asBuilder().setType(Type.PDT).setIanaIdentifier(9996L).build();
  }

  @Test
  public void testSuccess_ianaIdForExternalMonitoring() {
    registrar.asBuilder().setType(Type.EXTERNAL_MONITORING).setIanaIdentifier(9997L).build();
  }

  @Test
  public void testSuccess_emptyContactTypesAllowed() {
    persistSimpleResource(
        new RegistrarContact.Builder()
            .setParent(registrar)
            .setName("John Abused")
            .setEmailAddress("johnabuse@example.com")
            .setPhoneNumber("+1.2125551213")
            .setFaxNumber("+1.2125551213")
            // No setTypes(...)
            .build());
    for (RegistrarContact rc : registrar.getContacts()) {
      rc.toJsonMap();
    }
  }

  @Test
  public void testSuccess_getContactsByType() {
    RegistrarContact newTechContact =
        persistSimpleResource(
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("Jake Tech")
                .setEmailAddress("jaketech@example.com")
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(true)
                .setPhoneNumber("+1.2125551213")
                .setFaxNumber("+1.2125551213")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.TECH))
                .build());
    RegistrarContact newTechAbuseContact =
        persistSimpleResource(
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("Jim Tech-Abuse")
                .setEmailAddress("jimtechAbuse@example.com")
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(true)
                .setPhoneNumber("+1.2125551213")
                .setFaxNumber("+1.2125551213")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.TECH, RegistrarContact.Type.ABUSE))
                .build());
    ImmutableSortedSet<RegistrarContact> techContacts =
        registrar.getContactsOfType(RegistrarContact.Type.TECH);
    assertThat(techContacts).containsExactly(newTechContact, newTechAbuseContact).inOrder();
    ImmutableSortedSet<RegistrarContact> abuseContacts =
        registrar.getContactsOfType(RegistrarContact.Type.ABUSE);
    assertThat(abuseContacts).containsExactly(newTechAbuseContact, abuseAdminContact).inOrder();
  }

  @Test
  public void testFailure_missingRegistrarType() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Registrar.Builder().setRegistrarName("blah").build());
    assertThat(thrown).hasMessageThat().contains("Registrar type cannot be null");
  }

  @Test
  public void testFailure_missingRegistrarName() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new Registrar.Builder().setClientId("blahid").setType(Registrar.Type.TEST).build());
    assertThat(thrown).hasMessageThat().contains("Registrar name cannot be null");
  }

  @Test
  public void testFailure_missingAddress() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new Registrar.Builder()
                    .setClientId("blahid")
                    .setType(Registrar.Type.TEST)
                    .setRegistrarName("Blah Co")
                    .build());
    assertThat(thrown)
        .hasMessageThat()
        .contains("Must specify at least one of localized or internationalized address");
  }

  @Test
  public void testFailure_badIanaIdForInternal() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Registrar.Builder().setType(Type.INTERNAL).setIanaIdentifier(8L).build());
  }

  @Test
  public void testFailure_badIanaIdForPdt() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Registrar.Builder().setType(Type.PDT).setIanaIdentifier(8L).build());
  }

  @Test
  public void testFailure_badIanaIdForExternalMonitoring() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            registrar.asBuilder().setType(Type.EXTERNAL_MONITORING).setIanaIdentifier(8L).build());
  }

  @Test
  public void testFailure_missingIanaIdForReal() {
    assertThrows(
        IllegalArgumentException.class, () -> new Registrar.Builder().setType(Type.REAL).build());
  }

  @Test
  public void testFailure_missingIanaIdForInternal() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Registrar.Builder().setType(Type.INTERNAL).build());
  }

  @Test
  public void testFailure_missingIanaIdForPdt() {
    assertThrows(
        IllegalArgumentException.class, () -> new Registrar.Builder().setType(Type.PDT).build());
  }

  @Test
  public void testFailure_missingIanaIdForExternalMonitoring() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Registrar.Builder().setType(Type.EXTERNAL_MONITORING).build());
  }

  @Test
  public void testFailure_phonePasscodeTooShort() {
    assertThrows(
        IllegalArgumentException.class, () -> new Registrar.Builder().setPhonePasscode("0123"));
  }

  @Test
  public void testFailure_phonePasscodeTooLong() {
    assertThrows(
        IllegalArgumentException.class, () -> new Registrar.Builder().setPhonePasscode("012345"));
  }

  @Test
  public void testFailure_phonePasscodeInvalidCharacters() {
    assertThrows(
        IllegalArgumentException.class, () -> new Registrar.Builder().setPhonePasscode("code1"));
  }

  @Test
  public void testSuccess_setAllowedTlds() {
    assertThat(
            registrar
                .asBuilder()
                .setAllowedTlds(ImmutableSet.of("xn--q9jyb4c"))
                .build()
                .getAllowedTlds())
        .containsExactly("xn--q9jyb4c");
  }

  @Test
  public void testSuccess_setAllowedTldsUncached() {
    assertThat(
            registrar
                .asBuilder()
                .setAllowedTldsUncached(ImmutableSet.of("xn--q9jyb4c"))
                .build()
                .getAllowedTlds())
        .containsExactly("xn--q9jyb4c");
  }

  @Test
  public void testFailure_setAllowedTlds_nonexistentTld() {
    assertThrows(
        IllegalArgumentException.class,
        () -> registrar.asBuilder().setAllowedTlds(ImmutableSet.of("bad")));
  }

  @Test
  public void testFailure_setAllowedTldsUncached_nonexistentTld() {
    assertThrows(
        IllegalArgumentException.class,
        () -> registrar.asBuilder().setAllowedTldsUncached(ImmutableSet.of("bad")));
  }

  @Test
  public void testFailure_driveFolderId_asFullUrl() {
    String driveFolderId =
        "https://drive.google.com/drive/folders/1j3v7RZkU25DjbTx2-Q93H04zKOBau89M";
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> registrar.asBuilder().setDriveFolderId(driveFolderId));
    assertThat(thrown).hasMessageThat().isEqualTo("Drive folder ID must not be a full URL");
  }

  @Test
  public void testFailure_nullEmail() {
    NullPointerException thrown =
        assertThrows(NullPointerException.class, () -> registrar.asBuilder().setEmailAddress(null));
    assertThat(thrown).hasMessageThat().isEqualTo("Provided email was null");
  }

  @Test
  public void testFailure_invalidEmail() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> registrar.asBuilder().setEmailAddress("lolcat"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Provided email lolcat is not a valid email address");
  }

  @Test
  public void testFailure_emptyEmail() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> registrar.asBuilder().setEmailAddress(""));
    assertThat(thrown).hasMessageThat().isEqualTo("Provided email  is not a valid email address");
  }

  @Test
  public void testFailure_nullIcannReferralEmail() {
    NullPointerException thrown =
        assertThrows(
            NullPointerException.class, () -> registrar.asBuilder().setIcannReferralEmail(null));
    assertThat(thrown).hasMessageThat().isEqualTo("Provided email was null");
  }

  @Test
  public void testFailure_invalidIcannReferralEmail() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> registrar.asBuilder().setIcannReferralEmail("lolcat"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Provided email lolcat is not a valid email address");
  }

  @Test
  public void testFailure_emptyIcannReferralEmail() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> registrar.asBuilder().setEmailAddress(""));
    assertThat(thrown).hasMessageThat().isEqualTo("Provided email  is not a valid email address");
  }

  @Test
  public void testSuccess_setAllowedTldsUncached_newTldNotInCache() {
    int origSingletonCacheRefreshSeconds =
        RegistryConfig.CONFIG_SETTINGS.get().caching.singletonCacheRefreshSeconds;
    // Sanity check for Gradle-based open-source build.
    checkState(
        origSingletonCacheRefreshSeconds == 0, "singletonCacheRefreshSeconds expected to be 0.");
    try {
      // Cache duration in tests is 0. To make sure the data isn't in the cache we have to set it
      // to a higher value and reset the cache.
      RegistryConfig.CONFIG_SETTINGS.get().caching.singletonCacheRefreshSeconds = 600;
      Registries.resetCache();
      // Make sure the TLD we want to create doesn't exist yet.
      // This is also important because getTlds fills out the cache when used.
      assertThat(Registries.getTlds()).doesNotContain("newtld");
      // We can't use createTld here because it fails when the cache is used.
      persistResource(newRegistry("newtld", "NEWTLD"));
      // Make sure we set up the cache correctly, so the newly created TLD isn't in the cache
      assertThat(Registries.getTlds()).doesNotContain("newtld");

      // Test that the uncached version works
      assertThat(
              registrar
                  .asBuilder()
                  .setAllowedTldsUncached(ImmutableSet.of("newtld"))
                  .build()
                  .getAllowedTlds())
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
      assertThat(Registries.getTlds()).doesNotContain("newtld");
    } finally {
      RegistryConfig.CONFIG_SETTINGS.get().caching.singletonCacheRefreshSeconds =
          origSingletonCacheRefreshSeconds;
      Registries.resetCache();
    }
  }

  @Test
  public void testLoadByClientIdCached_isTransactionless() {
    tm().transact(
            () -> {
              assertThat(Registrar.loadByClientIdCached("registrar")).isPresent();
              // Load something as a control to make sure we are seeing loaded keys in the session
              // cache.
              ofy().load().entity(abuseAdminContact).now();
              assertThat(ofy().getSessionKeys()).contains(Key.create(abuseAdminContact));
              assertThat(ofy().getSessionKeys()).doesNotContain(Key.create(registrar));
            });
    ofy().clearSessionCache();
    // Conversely, loads outside of a transaction should end up in the session cache.
    assertThat(Registrar.loadByClientIdCached("registrar")).isPresent();
    assertThat(ofy().getSessionKeys()).contains(Key.create(registrar));
  }

  @Test
  public void testFailure_loadByClientId_clientIdIsNull() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> Registrar.loadByClientId(null));
    assertThat(thrown).hasMessageThat().contains("clientId must be specified");
  }

  @Test
  public void testFailure_loadByClientId_clientIdIsEmpty() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> Registrar.loadByClientId(""));
    assertThat(thrown).hasMessageThat().contains("clientId must be specified");
  }

  @Test
  public void testFailure_loadByClientIdCached_clientIdIsNull() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> Registrar.loadByClientIdCached(null));
    assertThat(thrown).hasMessageThat().contains("clientId must be specified");
  }

  @Test
  public void testFailure_loadByClientIdCached_clientIdIsEmpty() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> Registrar.loadByClientIdCached(""));
    assertThat(thrown).hasMessageThat().contains("clientId must be specified");
  }
}
