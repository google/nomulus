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

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT3;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT3_HASH;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT_HASH;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.flows.certs.CertificateChecker;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.State;
import google.registry.model.registrar.Registrar.Type;
import google.registry.persistence.VKey;
import google.registry.testing.AppEngineExtension;
import google.registry.util.CidrAddressBlock;
import java.util.Optional;
import org.joda.money.CurrencyUnit;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link UpdateRegistrarCommand}. */
class UpdateRegistrarCommandTest extends CommandTestCase<UpdateRegistrarCommand> {

  @BeforeEach
  void beforeEach() {
    command.certificateChecker =
        new CertificateChecker(
            ImmutableSortedMap.of(START_OF_TIME, 825, DateTime.parse("2020-09-01T00:00:00Z"), 398),
            30,
            2048,
            ImmutableSet.of("secp256r1", "secp384r1"),
            fakeClock);
  }

  @Test
  void testSuccess_alsoUpdateInCloudSql() throws Exception {
    assertThat(loadRegistrar("NewRegistrar").verifyPassword("some_password")).isFalse();
    jpaTm().transact(() -> jpaTm().insert(loadRegistrar("NewRegistrar")));
    runCommand("--password=some_password", "--force", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").verifyPassword("some_password")).isTrue();
    assertThat(
            jpaTm()
                .transact(() -> jpaTm().load(VKey.createSql(Registrar.class, "NewRegistrar")))
                .verifyPassword("some_password"))
        .isTrue();
  }

  @Test
  void testSuccess_password() throws Exception {
    assertThat(loadRegistrar("NewRegistrar").verifyPassword("some_password")).isFalse();
    runCommand("--password=some_password", "--force", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").verifyPassword("some_password")).isTrue();
  }

  @Test
  void testSuccess_registrarType() throws Exception {
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setType(Registrar.Type.OTE)
            .setIanaIdentifier(null)
            .build());
    assertThat(loadRegistrar("NewRegistrar").getType()).isEqualTo(Type.OTE);
    runCommand("--registrar_type=TEST", "--force", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getType()).isEqualTo(Type.TEST);
  }

  @Test
  void testFailure_noPasscodeOnChangeToReal() {
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setType(Registrar.Type.OTE)
            .setIanaIdentifier(null)
            .setPhonePasscode(null)
            .build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommand("--registrar_type=REAL", "--iana_id=1000", "--force", "NewRegistrar"));
    assertThat(thrown).hasMessageThat().contains("--passcode is required for REAL registrars.");
  }

  @Test
  void testSuccess_registrarState() throws Exception {
    assertThat(loadRegistrar("NewRegistrar").getState()).isEqualTo(State.ACTIVE);
    runCommand("--registrar_state=SUSPENDED", "--force", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getState()).isEqualTo(State.SUSPENDED);
  }

  @Test
  void testSuccess_allowedTlds() throws Exception {
    persistWhoisAbuseContact();
    createTlds("xn--q9jyb4c", "foobar");
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setAllowedTlds(ImmutableSet.of("xn--q9jyb4c"))
            .build());
    runCommandInEnvironment(
        RegistryToolEnvironment.PRODUCTION,
        "--allowed_tlds=xn--q9jyb4c,foobar",
        "--force",
        "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getAllowedTlds())
        .containsExactly("xn--q9jyb4c", "foobar");
  }

  @Test
  void testSuccess_addAllowedTlds() throws Exception {
    persistWhoisAbuseContact();
    createTlds("xn--q9jyb4c", "foo", "bar");
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setAllowedTlds(ImmutableSet.of("xn--q9jyb4c"))
            .build());
    runCommandInEnvironment(
        RegistryToolEnvironment.PRODUCTION,
        "--add_allowed_tlds=foo,bar",
        "--force",
        "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getAllowedTlds())
        .containsExactly("xn--q9jyb4c", "foo", "bar");
  }

  @Test
  void testSuccess_addAllowedTldsWithDupes() throws Exception {
    persistWhoisAbuseContact();
    createTlds("xn--q9jyb4c", "foo", "bar");
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setAllowedTlds(ImmutableSet.of("xn--q9jyb4c"))
            .build());
    runCommandInEnvironment(
        RegistryToolEnvironment.PRODUCTION,
        "--add_allowed_tlds=xn--q9jyb4c,foo,bar",
        "--force",
        "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getAllowedTlds())
        .isEqualTo(ImmutableSet.of("xn--q9jyb4c", "foo", "bar"));
  }

  @Test
  void testSuccess_allowedTldsInNonProductionEnvironment() throws Exception {
    createTlds("xn--q9jyb4c", "foobar");
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setAllowedTlds(ImmutableSet.of("xn--q9jyb4c"))
            .build());
    runCommandInEnvironment(
        RegistryToolEnvironment.SANDBOX,
        "--allowed_tlds=xn--q9jyb4c,foobar",
        "--force",
        "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getAllowedTlds())
        .containsExactly("xn--q9jyb4c", "foobar");
  }

  @Test
  void testSuccess_allowedTldsInPdtRegistrar() throws Exception {
    createTlds("xn--q9jyb4c", "foobar");
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setType(Type.PDT)
            .setIanaIdentifier(9995L)
            .setAllowedTlds(ImmutableSet.of("xn--q9jyb4c"))
            .build());
    runCommandInEnvironment(
        RegistryToolEnvironment.PRODUCTION,
        "--allowed_tlds=xn--q9jyb4c,foobar",
        "--force",
        "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getAllowedTlds())
        .containsExactly("xn--q9jyb4c", "foobar");
  }

  @Test
  void testSuccess_ipAllowList() throws Exception {
    assertThat(loadRegistrar("NewRegistrar").getIpAddressAllowList()).isEmpty();
    runCommand("--ip_allow_list=192.168.1.1,192.168.0.2/16", "--force", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getIpAddressAllowList())
        .containsExactly(
            CidrAddressBlock.create("192.168.1.1"), CidrAddressBlock.create("192.168.0.2/16"))
        .inOrder();
  }

  @Test
  void testSuccess_clearIpAllowList_useNull() throws Exception {
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setIpAddressAllowList(
                ImmutableList.of(
                    CidrAddressBlock.create("192.168.1.1"),
                    CidrAddressBlock.create("192.168.0.2/16")))
            .build());
    assertThat(loadRegistrar("NewRegistrar").getIpAddressAllowList()).isNotEmpty();
    runCommand("--ip_allow_list=null", "--force", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getIpAddressAllowList()).isEmpty();
  }

  @Test
  void testSuccess_clearIpAllowList_useEmpty() throws Exception {
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setIpAddressAllowList(
                ImmutableList.of(
                    CidrAddressBlock.create("192.168.1.1"),
                    CidrAddressBlock.create("192.168.0.2/16")))
            .build());
    assertThat(loadRegistrar("NewRegistrar").getIpAddressAllowList()).isNotEmpty();
    runCommand("--ip_allow_list=", "--force", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getIpAddressAllowList()).isEmpty();
  }

  @Test
  void testSuccess_certFile() throws Exception {
    fakeClock.setTo(DateTime.parse("2020-11-01T00:00:00Z"));
    Registrar registrar = loadRegistrar("NewRegistrar");
    assertThat(registrar.getClientCertificate()).isNull();
    assertThat(registrar.getClientCertificateHash()).isNull();
    runCommand("--cert_file=" + getCertFilename(SAMPLE_CERT3), "--force", "NewRegistrar");
    registrar = loadRegistrar("NewRegistrar");
    // NB: Hash was computed manually using 'openssl x509 -fingerprint -sha256 -in ...' and then
    // converting the result from a hex string to non-padded base64 encoded string.
    assertThat(registrar.getClientCertificate()).isEqualTo(SAMPLE_CERT3);
    assertThat(registrar.getClientCertificateHash()).isEqualTo(SAMPLE_CERT3_HASH);
  }

  @Test
  void testFail_certFileWithViolation() throws Exception {
    fakeClock.setTo(DateTime.parse("2020-11-01T00:00:00Z"));
    Registrar registrar = loadRegistrar("NewRegistrar");
    assertThat(registrar.getClientCertificate()).isNull();
    assertThat(registrar.getClientCertificateHash()).isNull();
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommand("--cert_file=" + getCertFilename(), "--force", "NewRegistrar"));
    assertThat(thrown.getMessage())
        .isEqualTo(
            "Certificate validity period is too long; it must be less than or equal to 398"
                + " days.");
    assertThat(registrar.getClientCertificate()).isNull();
  }

  @Test
  void testFail_certFileWithMultipleViolations() throws Exception {
    fakeClock.setTo(DateTime.parse("2055-10-01T00:00:00Z"));
    Registrar registrar = loadRegistrar("NewRegistrar");
    assertThat(registrar.getClientCertificate()).isNull();
    assertThat(registrar.getClientCertificateHash()).isNull();
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommand("--cert_file=" + getCertFilename(), "--force", "NewRegistrar"));
    assertThat(thrown.getMessage())
        .isEqualTo(
            "Certificate is expired.\nCertificate validity period is too long; it must be less"
                + " than or equal to 398 days.");
    assertThat(registrar.getClientCertificate()).isNull();
  }

  @Test
  void testFail_failoverCertFileWithViolation() throws Exception {
    fakeClock.setTo(DateTime.parse("2020-11-01T00:00:00Z"));
    Registrar registrar = loadRegistrar("NewRegistrar");
    assertThat(registrar.getFailoverClientCertificate()).isNull();
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommand("--failover_cert_file=" + getCertFilename(), "--force", "NewRegistrar"));
    assertThat(thrown.getMessage())
        .isEqualTo(
            "Certificate validity period is too long; it must be less than or equal to 398"
                + " days.");
    assertThat(registrar.getFailoverClientCertificate()).isNull();
  }

  @Test
  void testFail_failoverCertFileWithMultipleViolations() throws Exception {
    fakeClock.setTo(DateTime.parse("2055-10-01T00:00:00Z"));
    Registrar registrar = loadRegistrar("NewRegistrar");
    assertThat(registrar.getFailoverClientCertificate()).isNull();
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommand("--failover_cert_file=" + getCertFilename(), "--force", "NewRegistrar"));
    assertThat(thrown.getMessage())
        .isEqualTo(
            "Certificate is expired.\nCertificate validity period is too long; it must be less"
                + " than or equal to 398 days.");
    assertThat(registrar.getFailoverClientCertificate()).isNull();
  }

  @Test
  void testSuccess_failoverCertFile() throws Exception {
    fakeClock.setTo(DateTime.parse("2020-11-01T00:00:00Z"));
    Registrar registrar = loadRegistrar("NewRegistrar");
    assertThat(registrar.getFailoverClientCertificate()).isNull();
    runCommand("--failover_cert_file=" + getCertFilename(SAMPLE_CERT3), "--force", "NewRegistrar");
    registrar = loadRegistrar("NewRegistrar");
    assertThat(registrar.getFailoverClientCertificate()).isEqualTo(SAMPLE_CERT3);
  }

  @Test
  void testSuccess_certHash() throws Exception {
    assertThat(loadRegistrar("NewRegistrar").getClientCertificateHash()).isNull();
    runCommand("--cert_hash=" + SAMPLE_CERT_HASH, "--force", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getClientCertificateHash())
        .isEqualTo(SAMPLE_CERT_HASH);
  }

  @Test
  void testSuccess_clearCert() throws Exception {
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setClientCertificate(SAMPLE_CERT, DateTime.now(UTC))
            .build());
    assertThat(isNullOrEmpty(loadRegistrar("NewRegistrar").getClientCertificate())).isFalse();
    runCommand("--cert_file=/dev/null", "--force", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getClientCertificate()).isNull();
  }

  @Test
  void testSuccess_clearCertHash() throws Exception {
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setClientCertificateHash(SAMPLE_CERT_HASH)
            .build());
    assertThat(isNullOrEmpty(loadRegistrar("NewRegistrar").getClientCertificateHash())).isFalse();
    runCommand("--cert_hash=null", "--force", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getClientCertificateHash()).isNull();
  }

  @Test
  void testSuccess_ianaId() throws Exception {
    assertThat(loadRegistrar("NewRegistrar").getIanaIdentifier()).isEqualTo(8);
    runCommand("--iana_id=12345", "--force", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getIanaIdentifier()).isEqualTo(12345);
  }

  @Test
  void testSuccess_billingId() throws Exception {
    assertThat(loadRegistrar("NewRegistrar").getBillingIdentifier()).isNull();
    runCommand("--billing_id=12345", "--force", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getBillingIdentifier()).isEqualTo(12345);
  }

  @Test
  void testSuccess_poNumber() throws Exception {
    assertThat(loadRegistrar("NewRegistrar").getPoNumber()).isEmpty();
    runCommand("--po_number=52345", "--force", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getPoNumber()).hasValue("52345");
  }

  @Test
  void testSuccess_billingAccountMap() throws Exception {
    assertThat(loadRegistrar("NewRegistrar").getBillingAccountMap()).isEmpty();
    runCommand("--billing_account_map=USD=abc123,JPY=789xyz", "--force", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getBillingAccountMap())
        .containsExactly(CurrencyUnit.USD, "abc123", CurrencyUnit.JPY, "789xyz");
  }

  @Test
  void testFailure_billingAccountMap_doesNotContainEntryForTldAllowed() {
    createTlds("foo");
    assertThat(loadRegistrar("NewRegistrar").getBillingAccountMap()).isEmpty();
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommand(
                    "--billing_account_map=JPY=789xyz",
                    "--allowed_tlds=foo",
                    "--force",
                    "--registrar_type=REAL",
                    "NewRegistrar"));
    assertThat(thrown).hasMessageThat().contains("USD");
  }

  @Test
  void testSuccess_billingAccountMap_onlyAppliesToRealRegistrar() throws Exception {
    createTlds("foo");
    assertThat(loadRegistrar("NewRegistrar").getBillingAccountMap()).isEmpty();
    runCommand("--billing_account_map=JPY=789xyz", "--allowed_tlds=foo", "--force", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getBillingAccountMap())
        .containsExactly(CurrencyUnit.JPY, "789xyz");
  }

  @Test
  void testSuccess_billingAccountMap_partialUpdate() throws Exception {
    createTlds("foo");
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setBillingAccountMap(
                ImmutableMap.of(CurrencyUnit.USD, "abc123", CurrencyUnit.JPY, "789xyz"))
            .build());
    runCommand("--billing_account_map=JPY=123xyz", "--allowed_tlds=foo", "--force", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getBillingAccountMap())
        .containsExactly(CurrencyUnit.JPY, "123xyz", CurrencyUnit.USD, "abc123");
  }

  @Test
  void testSuccess_streetAddress() throws Exception {
    runCommand(
        "--street=\"1234 Main St\"",
        "--street \"4th Floor\"",
        "--street \"Suite 1\"",
        "--city Brooklyn",
        "--state NY",
        "--zip 11223",
        "--cc US",
        "--force",
        "NewRegistrar");

    Registrar registrar = loadRegistrar("NewRegistrar");
    assertThat(registrar.getLocalizedAddress() != null).isTrue();
    assertThat(registrar.getLocalizedAddress().getStreet()).hasSize(3);
    assertThat(registrar.getLocalizedAddress().getStreet().get(0)).isEqualTo("1234 Main St");
    assertThat(registrar.getLocalizedAddress().getStreet().get(1)).isEqualTo("4th Floor");
    assertThat(registrar.getLocalizedAddress().getStreet().get(2)).isEqualTo("Suite 1");
    assertThat(registrar.getLocalizedAddress().getCity()).isEqualTo("Brooklyn");
    assertThat(registrar.getLocalizedAddress().getState()).isEqualTo("NY");
    assertThat(registrar.getLocalizedAddress().getZip()).isEqualTo("11223");
    assertThat(registrar.getLocalizedAddress().getCountryCode()).isEqualTo("US");
  }

  @Test
  void testSuccess_blockPremiumNames() throws Exception {
    assertThat(loadRegistrar("NewRegistrar").getBlockPremiumNames()).isFalse();
    runCommandForced("--block_premium=true", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getBlockPremiumNames()).isTrue();
  }

  @Test
  void testSuccess_resetBlockPremiumNames() throws Exception {
    persistResource(loadRegistrar("NewRegistrar").asBuilder().setBlockPremiumNames(true).build());
    runCommandForced("--block_premium=false", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getBlockPremiumNames()).isFalse();
  }

  @Test
  void testSuccess_allowRegistryLock() throws Exception {
    assertThat(loadRegistrar("NewRegistrar").isRegistryLockAllowed()).isFalse();
    runCommandForced("--registry_lock_allowed=true", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").isRegistryLockAllowed()).isTrue();
  }

  @Test
  void testSuccess_disallowRegistryLock() throws Exception {
    persistResource(loadRegistrar("NewRegistrar").asBuilder().setRegistryLockAllowed(true).build());
    runCommandForced("--registry_lock_allowed=false", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").isRegistryLockAllowed()).isFalse();
  }

  @Test
  void testSuccess_unspecifiedBooleansArentChanged() throws Exception {
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setBlockPremiumNames(true)
            .setContactsRequireSyncing(true)
            .build());
    // Make some unrelated change where we don't specify the flags for the booleans.
    runCommandForced("--billing_id=12345", "NewRegistrar");
    // Make sure that the boolean fields didn't get reset back to false.
    Registrar reloadedRegistrar = loadRegistrar("NewRegistrar");
    assertThat(reloadedRegistrar.getBlockPremiumNames()).isTrue();
    assertThat(reloadedRegistrar.getContactsRequireSyncing()).isTrue();
  }

  @Test
  void testSuccess_updateMultiple() throws Exception {
    assertThat(loadRegistrar("TheRegistrar").getState()).isEqualTo(State.ACTIVE);
    assertThat(loadRegistrar("NewRegistrar").getState()).isEqualTo(State.ACTIVE);
    runCommandForced("--registrar_state=SUSPENDED", "TheRegistrar", "NewRegistrar");
    assertThat(loadRegistrar("TheRegistrar").getState()).isEqualTo(State.SUSPENDED);
    assertThat(loadRegistrar("NewRegistrar").getState()).isEqualTo(State.SUSPENDED);
  }

  @Test
  void testSuccess_resetOptionalParamsNullString() throws Exception {
    Registrar registrar = loadRegistrar("NewRegistrar");
    registrar =
        persistResource(
            registrar
                .asBuilder()
                .setType(Type.PDT) // for non-null IANA ID
                .setIanaIdentifier(9995L)
                .setBillingIdentifier(1L)
                .setPhoneNumber("+1.2125555555")
                .setFaxNumber("+1.2125555556")
                .setUrl("http://www.example.tld")
                .setDriveFolderId("id")
                .build());

    assertThat(registrar.getIanaIdentifier()).isNotNull();
    assertThat(registrar.getBillingIdentifier()).isNotNull();
    assertThat(registrar.getPhoneNumber()).isNotNull();
    assertThat(registrar.getFaxNumber()).isNotNull();
    assertThat(registrar.getUrl()).isNotNull();
    assertThat(registrar.getDriveFolderId()).isNotNull();

    runCommand(
        "--registrar_type=TEST", // necessary for null IANA ID
        "--iana_id=null",
        "--billing_id=null",
        "--phone=null",
        "--fax=null",
        "--url=null",
        "--drive_folder_id=null",
        "--force",
        "NewRegistrar");

    registrar = loadRegistrar("NewRegistrar");
    assertThat(registrar.getIanaIdentifier()).isNull();
    assertThat(registrar.getBillingIdentifier()).isNull();
    assertThat(registrar.getPhoneNumber()).isNull();
    assertThat(registrar.getFaxNumber()).isNull();
    assertThat(registrar.getUrl()).isNull();
    assertThat(registrar.getDriveFolderId()).isNull();
  }

  @Test
  void testSuccess_resetOptionalParamsEmptyString() throws Exception {
    Registrar registrar = loadRegistrar("NewRegistrar");
    registrar =
        persistResource(
            registrar
                .asBuilder()
                .setType(Type.PDT) // for non-null IANA ID
                .setIanaIdentifier(9995L)
                .setBillingIdentifier(1L)
                .setPhoneNumber("+1.2125555555")
                .setFaxNumber("+1.2125555556")
                .setUrl("http://www.example.tld")
                .setDriveFolderId("id")
                .build());

    assertThat(registrar.getIanaIdentifier()).isNotNull();
    assertThat(registrar.getBillingIdentifier()).isNotNull();
    assertThat(registrar.getPhoneNumber()).isNotNull();
    assertThat(registrar.getFaxNumber()).isNotNull();
    assertThat(registrar.getUrl()).isNotNull();
    assertThat(registrar.getDriveFolderId()).isNotNull();

    runCommand(
        "--registrar_type=TEST", // necessary for null IANA ID
        "--iana_id=",
        "--billing_id=",
        "--phone=",
        "--fax=",
        "--url=",
        "--drive_folder_id=",
        "--force",
        "NewRegistrar");

    registrar = loadRegistrar("NewRegistrar");
    assertThat(registrar.getIanaIdentifier()).isNull();
    assertThat(registrar.getBillingIdentifier()).isNull();
    assertThat(registrar.getPhoneNumber()).isNull();
    assertThat(registrar.getFaxNumber()).isNull();
    assertThat(registrar.getUrl()).isNull();
    assertThat(registrar.getDriveFolderId()).isNull();
  }

  @Test
  void testSuccess_setIcannEmail() throws Exception {
    runCommand("--icann_referral_email=foo@bar.test", "--force", "TheRegistrar");
    Registrar registrar = loadRegistrar("TheRegistrar");
    assertThat(registrar.getIcannReferralEmail()).isEqualTo("foo@bar.test");
    assertThat(registrar.getEmailAddress()).isEqualTo("foo@bar.test");
  }

  @Test
  void testSuccess_setEmail() throws Exception {
    runCommand("--email=foo@bar.baz", "--force", "TheRegistrar");
    Registrar registrar = loadRegistrar("TheRegistrar");
    assertThat(registrar.getEmailAddress()).isEqualTo("foo@bar.baz");
  }

  @Test
  void testSuccess_setWhoisServer_works() throws Exception {
    runCommand("--whois=whois.goth.black", "--force", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getWhoisServer()).isEqualTo("whois.goth.black");
  }

  @Test
  void testSuccess_triggerGroupSyncing_works() throws Exception {
    persistResource(
        loadRegistrar("NewRegistrar").asBuilder().setContactsRequireSyncing(false).build());
    assertThat(loadRegistrar("NewRegistrar").getContactsRequireSyncing()).isFalse();
    runCommand("--sync_groups=true", "--force", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getContactsRequireSyncing()).isTrue();
  }

  @Test
  void testFailure_invalidRegistrarType() {
    assertThrows(
        ParameterException.class,
        () -> runCommand("--registrar_type=INVALID_TYPE", "--force", "NewRegistrar"));
  }

  @Test
  void testFailure_invalidRegistrarState() {
    assertThrows(
        ParameterException.class,
        () -> runCommand("--registrar_state=INVALID_STATE", "--force", "NewRegistrar"));
  }

  @Test
  void testFailure_negativeIanaId() {
    assertThrows(
        IllegalArgumentException.class,
        () -> runCommand("--iana_id=-1", "--force", "NewRegistrar"));
  }

  @Test
  void testFailure_nonIntegerIanaId() {
    assertThrows(
        ParameterException.class, () -> runCommand("--iana_id=ABC123", "--force", "NewRegistrar"));
  }

  @Test
  void testFailure_negativeBillingId() {
    assertThrows(
        IllegalArgumentException.class,
        () -> runCommand("--billing_id=-1", "--force", "NewRegistrar"));
  }

  @Test
  void testFailure_nonIntegerBillingId() {
    assertThrows(
        ParameterException.class,
        () -> runCommand("--billing_id=ABC123", "--force", "NewRegistrar"));
  }

  @Test
  void testFailure_passcodeTooShort() {
    assertThrows(
        IllegalArgumentException.class,
        () -> runCommand("--passcode=0123", "--force", "NewRegistrar"));
  }

  @Test
  void testFailure_passcodeTooLong() {
    assertThrows(
        IllegalArgumentException.class,
        () -> runCommand("--passcode=012345", "--force", "NewRegistrar"));
  }

  @Test
  void testFailure_invalidPasscode() {
    assertThrows(
        IllegalArgumentException.class,
        () -> runCommand("--passcode=code1", "--force", "NewRegistrar"));
  }

  @Test
  void testFailure_allowedTldDoesNotExist() {
    assertThrows(
        IllegalArgumentException.class,
        () -> runCommand("--allowed_tlds=foobar", "--force", "NewRegistrar"));
  }

  @Test
  void testFailure_addAllowedTldDoesNotExist() {
    assertThrows(
        IllegalArgumentException.class,
        () -> runCommand("--add_allowed_tlds=foobar", "--force", "NewRegistrar"));
  }

  @Test
  void testFailure_allowedTldsAndAddAllowedTlds() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommand("--allowed_tlds=bar", "--add_allowed_tlds=foo", "--force", "NewRegistrar"));
  }

  @Test
  void testFailure_setAllowedTldsWithoutAbuseContact() {
    createTlds("bar");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandInEnvironment(
                    RegistryToolEnvironment.PRODUCTION,
                    "--allowed_tlds=bar",
                    "--force",
                    "TheRegistrar"));
    assertThat(thrown).hasMessageThat().startsWith("Cannot modify allowed TLDs");
  }

  @Test
  void testFailure_addAllowedTldsWithoutAbuseContact() {
    createTlds("bar");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandInEnvironment(
                    RegistryToolEnvironment.PRODUCTION,
                    "--add_allowed_tlds=bar",
                    "--force",
                    "TheRegistrar"));
    assertThat(thrown).hasMessageThat().startsWith("Cannot modify allowed TLDs");
  }

  @Test
  void testFailure_invalidIpAllowList() {
    assertThrows(
        IllegalArgumentException.class,
        () -> runCommand("--ip_allow_list=foobarbaz", "--force", "NewRegistrar"));
  }

  @Test
  void testFailure_invalidCertFileContents() {
    assertThrows(
        Exception.class,
        () -> runCommand("--cert_file=" + writeToTmpFile("ABCDEF"), "--force", "NewRegistrar"));
  }

  @Test
  void testFailure_certHashAndCertFile() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommand(
                "--cert_file=" + getCertFilename(SAMPLE_CERT3),
                "--cert_hash=ABCDEF",
                "--force",
                "NewRegistrar"));
  }

  @Test
  void testFailure_missingClientId() {
    assertThrows(ParameterException.class, () -> runCommand("--force"));
  }

  @Test
  void testFailure_missingStreetLines() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommand(
                "--city Brooklyn",
                "--state NY",
                "--zip 11223",
                "--cc US",
                "--force",
                "NewRegistrar"));
  }

  @Test
  void testFailure_missingCity() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommand(
                "--street=\"1234 Main St\"",
                "--street \"4th Floor\"",
                "--street \"Suite 1\"",
                "--state NY",
                "--zip 11223",
                "--cc US",
                "--force",
                "NewRegistrar"));
  }

  @Test
  void testFailure_missingState() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommand(
                "--street=\"1234 Main St\"",
                "--street \"4th Floor\"",
                "--street \"Suite 1\"",
                "--city Brooklyn",
                "--zip 11223",
                "--cc US",
                "--force",
                "NewRegistrar"));
  }

  @Test
  void testFailure_missingZip() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommand(
                "--street=\"1234 Main St\"",
                "--street \"4th Floor\"",
                "--street \"Suite 1\"",
                "--city Brooklyn",
                "--state NY",
                "--cc US",
                "--force",
                "NewRegistrar"));
  }

  @Test
  void testFailure_missingCc() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommand(
                "--street=\"1234 Main St\"",
                "--street \"4th Floor\"",
                "--street \"Suite 1\"",
                "--city Brooklyn",
                "--state NY",
                "--zip 11223",
                "--force",
                "NewRegistrar"));
  }

  @Test
  void testFailure_missingInvalidCc() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommand(
                "--street=\"1234 Main St\"",
                "--street \"4th Floor\"",
                "--street \"Suite 1\"",
                "--city Brooklyn",
                "--state NY",
                "--zip 11223",
                "--cc USA",
                "--force",
                "NewRegistrar"));
  }

  @Test
  void testFailure_tooManyStreetLines() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommand(
                "--street \"Attn:Hey You Guys\"",
                "--street \"1234 Main St\"",
                "--street \"4th Floor\"",
                "--street \"Suite 1\"",
                "--city Brooklyn",
                "--state NY",
                "--zip 11223",
                "--cc USA",
                "--force",
                "NewRegistrar"));
  }

  @Test
  void testFailure_tooFewStreetLines() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommand(
                "--street",
                "--city Brooklyn",
                "--state NY",
                "--zip 11223",
                "--cc USA",
                "--force",
                "NewRegistrar"));
  }

  @Test
  void testFailure_unknownFlag() {
    assertThrows(
        ParameterException.class,
        () -> runCommand("--force", "--unrecognized_flag=foo", "NewRegistrar"));
  }

  @Test
  void testFailure_doesNotExist() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommand("--force", "ClientZ"));
    assertThat(thrown).hasMessageThat().contains("Registrar ClientZ not found");
  }

  @Test
  void testFailure_registrarNameSimilarToExisting() {
    // Note that "tHeRe GiStRaR" normalizes identically to "The Registrar", which is created by
    // AppEngineRule.
    assertThrows(
        IllegalArgumentException.class,
        () -> runCommand("--name tHeRe GiStRaR", "--force", "NewRegistrar"));
  }

  @Test
  void testSuccess_poNumberNotSpecified_doesntWipeOutExisting() throws Exception {
    Registrar registrar =
        persistResource(
            loadRegistrar("NewRegistrar").asBuilder().setPoNumber(Optional.of("1664")).build());
    assertThat(registrar.verifyPassword("some_password")).isFalse();
    runCommand("--password=some_password", "--force", "NewRegistrar");
    Registrar reloadedRegistrar = loadRegistrar("NewRegistrar");
    assertThat(reloadedRegistrar.verifyPassword("some_password")).isTrue();
    assertThat(reloadedRegistrar.getPoNumber()).hasValue("1664");
  }

  @Test
  void testSuccess_poNumber_canBeBlanked() throws Exception {
    persistResource(
        loadRegistrar("NewRegistrar").asBuilder().setPoNumber(Optional.of("1664")).build());
    runCommand("--po_number=null", "--force", "NewRegistrar");
    assertThat(loadRegistrar("NewRegistrar").getPoNumber()).isEmpty();
  }

  @Test
  void testFailure_badEmail() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommand("--email=lolcat", "--force", "NewRegistrar"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Provided email lolcat is not a valid email address");
  }

  private void persistWhoisAbuseContact() {
    persistResource(
        AppEngineExtension.makeRegistrarContact1()
            .asBuilder()
            .setVisibleInDomainWhoisAsAbuse(true)
            .build());
  }
}
