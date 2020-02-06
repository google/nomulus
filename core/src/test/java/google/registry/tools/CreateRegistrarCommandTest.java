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
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT_HASH;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.persistNewRegistrar;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Range;
import com.google.common.net.MediaType;
import google.registry.model.registrar.Registrar;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationWithCoverageRule;
import google.registry.schema.registrar.RegistrarDao;
import google.registry.testing.CertificateSamples;
import google.registry.testing.FakeClock;
import java.io.IOException;
import java.util.Optional;
import org.joda.money.CurrencyUnit;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;

/** Unit tests for {@link CreateRegistrarCommand}. */
public class CreateRegistrarCommandTest extends CommandTestCase<CreateRegistrarCommand> {

  private final FakeClock fakeClock = new FakeClock();

  @Rule
  public final JpaIntegrationWithCoverageRule jpaRule =
      new JpaTestRules.Builder().withClock(fakeClock).buildIntegrationWithCoverageRule();

  @Mock private AppEngineConnection connection;

  @Before
  public void init() {
    command.setConnection(connection);
  }

  @Test
  public void testSuccess() throws Exception {
    DateTime before = DateTime.now(UTC);
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");
    DateTime after = DateTime.now(UTC);

    // Clear the cache so that the CreateAutoTimestamp field gets reloaded.
    ofy().clearSessionCache();

    Optional<Registrar> registrarOptional = Registrar.loadByClientId("clientz");
    assertThat(registrarOptional).isPresent();
    Registrar registrar = registrarOptional.get();
    assertThat(registrar.verifyPassword("some_password")).isTrue();
    assertThat(registrar.getType()).isEqualTo(Registrar.Type.REAL);
    assertThat(registrar.getIanaIdentifier()).isEqualTo(8);
    assertThat(registrar.getState()).isEqualTo(Registrar.State.ACTIVE);
    assertThat(registrar.getAllowedTlds()).isEmpty();
    assertThat(registrar.getIpAddressWhitelist()).isEmpty();
    assertThat(registrar.getClientCertificateHash()).isNull();
    assertThat(registrar.getPhonePasscode()).isEqualTo("01234");
    assertThat(registrar.getCreationTime()).isIn(Range.closed(before, after));
    assertThat(registrar.getLastUpdateTime()).isEqualTo(registrar.getCreationTime());
    assertThat(registrar.getBlockPremiumNames()).isFalse();
    assertThat(registrar.isRegistryLockAllowed()).isFalse();
    assertThat(registrar.getPoNumber()).isEmpty();
    assertThat(registrar.getIcannReferralEmail()).isEqualTo("foo@bar.test");

    verify(connection)
        .sendPostRequest(
            eq("/_dr/admin/createGroups"),
            eq(ImmutableMap.of("clientId", "clientz")),
            eq(MediaType.PLAIN_TEXT_UTF_8),
            eq(new byte[0]));
  }

  @Test
  public void testSuccess_alsoSaveToCloudSql() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=\"some_password\"",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().verifyPassword("some_password")).isTrue();
    assertThat(RegistrarDao.checkExists("clientz")).isTrue();
  }

  @Test
  public void testSuccess_quotedPassword() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=\"some_password\"",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().verifyPassword("some_password")).isTrue();
  }

  @Test
  public void testSuccess_registrarTypeFlag() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=TEST",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().getType()).isEqualTo(Registrar.Type.TEST);
  }

  @Test
  public void testSuccess_registrarStateFlag() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--registrar_state=SUSPENDED",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().getState()).isEqualTo(Registrar.State.SUSPENDED);
  }

  @Test
  public void testSuccess_allowedTldsInNonProductionEnvironment() throws Exception {
    createTlds("xn--q9jyb4c", "foobar");

    runCommandInEnvironment(
        RegistryToolEnvironment.SANDBOX,
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--allowed_tlds=xn--q9jyb4c,foobar",
        "--billing_account_map=USD=123abc",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "--force",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().getAllowedTlds()).containsExactly("xn--q9jyb4c", "foobar");
  }

  @Test
  public void testSuccess_allowedTldsInPDT() throws Exception {
    createTlds("xn--q9jyb4c", "foobar");

    runCommandInEnvironment(
        RegistryToolEnvironment.PRODUCTION,
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=PDT",
        "--iana_id=9995",
        "--allowed_tlds=xn--q9jyb4c,foobar",
        "--billing_account_map=USD=123abc",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "--force",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().getAllowedTlds()).containsExactly("xn--q9jyb4c", "foobar");
  }

  @Test
  public void testSuccess_groupCreationCanBeDisabled() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=TEST",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--create_groups=false",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    verifyZeroInteractions(connection);
  }

  @Test
  public void testFailure_groupCreationFails() throws Exception {
    when(connection.sendPostRequest(
            ArgumentMatchers.anyString(),
            ArgumentMatchers.anyMap(),
            ArgumentMatchers.any(MediaType.class),
            ArgumentMatchers.any(byte[].class)))
        .thenThrow(new IOException("BAD ROBOT NO COOKIE"));
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=TEST",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertInStdout("Registrar created, but groups creation failed with error");
    assertInStdout("BAD ROBOT NO COOKIE");
  }

  @Test
  public void testSuccess_groupCreationDoesntOccurOnAlphaEnv() throws Exception {
    runCommandInEnvironment(
        RegistryToolEnvironment.ALPHA,
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=TEST",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "--force",
        "clientz");

    verifyZeroInteractions(connection);
  }

  @Test
  public void testSuccess_ipWhitelistFlag() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--ip_whitelist=192.168.1.1,192.168.0.2/16",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().getIpAddressWhitelist())
        .containsExactlyElementsIn(registrar.get().getIpAddressWhitelist())
        .inOrder();
  }

  @Test
  public void testSuccess_ipWhitelistFlagNull() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--ip_whitelist=null",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().getIpAddressWhitelist()).isEmpty();
  }

  @Test
  public void testSuccess_clientCertFileFlag() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--cert_file=" + getCertFilename(),
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().getClientCertificateHash())
        .isEqualTo(CertificateSamples.SAMPLE_CERT_HASH);
  }

  @Test
  public void testSuccess_clientCertHashFlag() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--cert_hash=" + SAMPLE_CERT_HASH,
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().getClientCertificate()).isNull();
    assertThat(registrar.get().getClientCertificateHash()).isEqualTo(SAMPLE_CERT_HASH);
  }

  @Test
  public void testSuccess_failoverClientCertFileFlag() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--failover_cert_file=" + getCertFilename(),
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrarOptional = Registrar.loadByClientId("clientz");
    assertThat(registrarOptional).isPresent();
    Registrar registrar = registrarOptional.get();
    assertThat(registrar.getClientCertificate()).isNull();
    assertThat(registrar.getClientCertificateHash()).isNull();
    assertThat(registrar.getFailoverClientCertificate()).isEqualTo(SAMPLE_CERT);
    assertThat(registrar.getFailoverClientCertificateHash()).isEqualTo(SAMPLE_CERT_HASH);
  }

  @Test
  public void testSuccess_ianaId() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=12345",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().getIanaIdentifier()).isEqualTo(12345);
  }

  @Test
  public void testSuccess_billingId() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--billing_id=12345",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().getBillingIdentifier()).isEqualTo(12345);
  }

  @Test
  public void testSuccess_poNumber() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--po_number=AA55G",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().getPoNumber()).hasValue("AA55G");
  }

  @Test
  public void testSuccess_billingAccountMap() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--billing_account_map=USD=abc123,JPY=789xyz",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().getBillingAccountMap())
        .containsExactly(CurrencyUnit.USD, "abc123", CurrencyUnit.JPY, "789xyz");
  }

  @Test
  public void testFailure_billingAccountMap_doesNotContainEntryForTldAllowed() {
    createTlds("foo");

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandInEnvironment(
                    RegistryToolEnvironment.SANDBOX,
                    "--name=blobio",
                    "--password=some_password",
                    "--registrar_type=REAL",
                    "--iana_id=8",
                    "--billing_account_map=JPY=789xyz",
                    "--allowed_tlds=foo",
                    "--passcode=01234",
                    "--icann_referral_email=foo@bar.test",
                    "--street=\"123 Fake St\"",
                    "--city Fakington",
                    "--state MA",
                    "--zip 00351",
                    "--cc US",
                    "--force",
                    "clientz"));
    assertThat(thrown).hasMessageThat().contains("USD");
  }

  @Test
  public void testSuccess_billingAccountMap_onlyAppliesToRealRegistrar() throws Exception {
    createTlds("foo");

    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=TEST",
        "--billing_account_map=JPY=789xyz",
        "--allowed_tlds=foo",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().getBillingAccountMap()).containsExactly(CurrencyUnit.JPY, "789xyz");
  }

  @Test
  public void testSuccess_streetAddress() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--street=\"1234 Main St\"",
        "--street \"4th Floor\"",
        "--street \"Suite 1\"",
        "--city Brooklyn",
        "--state NY",
        "--zip 11223",
        "--cc US",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "clientz");

    Optional<Registrar> registrarOptional = Registrar.loadByClientId("clientz");
    assertThat(registrarOptional).isPresent();
    Registrar registrar = registrarOptional.get();
    assertThat(registrar.getLocalizedAddress()).isNotNull();
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
  public void testSuccess_email() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--email=foo@foo.foo",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().getEmailAddress()).isEqualTo("foo@foo.foo");
  }

  @Test
  public void testSuccess_fallBackToIcannReferralEmail() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().getEmailAddress()).isEqualTo("foo@bar.test");
  }

  @Test
  public void testSuccess_url() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--url=http://foo.foo",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().getUrl()).isEqualTo("http://foo.foo");
  }

  @Test
  public void testSuccess_phone() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--phone=+1.2125556342",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().getPhoneNumber()).isEqualTo("+1.2125556342");
  }

  @Test
  public void testSuccess_optionalParamsAsNull() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=TEST",
        "--icann_referral_email=foo@bar.test",
        "--iana_id=null",
        "--billing_id=null",
        "--phone=null",
        "--fax=null",
        "--url=null",
        "--drive_folder_id=null",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrarOptional = Registrar.loadByClientId("clientz");
    assertThat(registrarOptional).isPresent();
    Registrar registrar = registrarOptional.get();
    assertThat(registrar.getIanaIdentifier()).isNull();
    assertThat(registrar.getBillingIdentifier()).isNull();
    assertThat(registrar.getPhoneNumber()).isNull();
    assertThat(registrar.getFaxNumber()).isNull();
    assertThat(registrar.getUrl()).isNull();
    assertThat(registrar.getDriveFolderId()).isNull();

  }

  @Test
  public void testSuccess_optionalParamsAsEmptyString() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=TEST",
        "--iana_id=",
        "--billing_id=",
        "--phone=",
        "--fax=",
        "--url=",
        "--drive_folder_id=",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrarOptional = Registrar.loadByClientId("clientz");
    assertThat(registrarOptional).isPresent();
    Registrar registrar = registrarOptional.get();
    assertThat(registrar.getIanaIdentifier()).isNull();
    assertThat(registrar.getBillingIdentifier()).isNull();
    assertThat(registrar.getPhoneNumber()).isNull();
    assertThat(registrar.getFaxNumber()).isNull();
    assertThat(registrar.getUrl()).isNull();
    assertThat(registrar.getDriveFolderId()).isNull();
  }

  @Test
  public void testSuccess_blockPremiumNames() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--block_premium=true",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().getBlockPremiumNames()).isTrue();
  }

  @Test
  public void testSuccess_noBlockPremiumNames() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--block_premium=false",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().getBlockPremiumNames()).isFalse();
  }

  @Test
  public void testSuccess_registryLockAllowed() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--registry_lock_allowed=true",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().isRegistryLockAllowed()).isTrue();
  }

  @Test
  public void testSuccess_registryLockDisallowed() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--registry_lock_allowed=false",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().isRegistryLockAllowed()).isFalse();
  }

  @Test
  public void testFailure_badPhoneNumber() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    "--name=blobio",
                    "--password=some_password",
                    "--registrar_type=REAL",
                    "--iana_id=8",
                    "--phone=+1.112.555.6342",
                    "--passcode=01234",
                    "--icann_referral_email=foo@bar.test",
                    "--street=\"123 Fake St\"",
                    "--city Fakington",
                    "--state MA",
                    "--zip 00351",
                    "--cc US",
                    "clientz"));
    assertThat(thrown).hasMessageThat().contains("phone");
  }

  @Test
  public void testFailure_badPhoneNumber2() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    "--name=blobio",
                    "--password=some_password",
                    "--registrar_type=REAL",
                    "--iana_id=8",
                    "--phone=+1.5555555555e",
                    "--passcode=01234",
                    "--icann_referral_email=foo@bar.test",
                    "--street=\"123 Fake St\"",
                    "--city Fakington",
                    "--state MA",
                    "--zip 00351",
                    "--cc US",
                    "clientz"));
    assertThat(thrown).hasMessageThat().contains("phone");
  }

  @Test
  public void testSuccess_fax() throws Exception {
    runCommandForced(
        "--name=blobio",
        "--password=some_password",
        "--registrar_type=REAL",
        "--iana_id=8",
        "--fax=+1.2125556342",
        "--passcode=01234",
        "--icann_referral_email=foo@bar.test",
        "--street=\"123 Fake St\"",
        "--city Fakington",
        "--state MA",
        "--zip 00351",
        "--cc US",
        "clientz");

    Optional<Registrar> registrar = Registrar.loadByClientId("clientz");
    assertThat(registrar).isPresent();
    assertThat(registrar.get().getFaxNumber()).isEqualTo("+1.2125556342");
  }

  @Test
  public void testFailure_missingRegistrarType() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--name=blobio",
                    "--password=some_password",
                    "--iana_id=8",
                    "--icann_referral_email=foo@bar.test",
                    "--street=\"123 Fake St\"",
                    "--city Fakington",
                    "--state MA",
                    "--zip 00351",
                    "--cc US",
                    "clientz"));
    assertThat(thrown).hasMessageThat().contains("Registrar type cannot be null");
  }

  @Test
  public void testFailure_invalidRegistrarType() {
    assertThrows(
        ParameterException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=INVALID_TYPE",
                "--iana_id=8",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "clientz"));
  }

  @Test
  public void testFailure_invalidRegistrarState() {
    assertThrows(
        ParameterException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--registrar_state=INVALID_STATE",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "clientz"));
  }

  @Test
  public void testFailure_allowedTldDoesNotExist() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--allowed_tlds=foobar",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "clientz"));
  }

  @Test
  public void testFailure_allowedTldsInRealWithoutAbuseContact() {
    createTlds("xn--q9jyb4c", "foobar");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandInEnvironment(
                    RegistryToolEnvironment.PRODUCTION,
                    "--name=blobio",
                    "--password=some_password",
                    "--registrar_type=REAL",
                    "--iana_id=8",
                    "--allowed_tlds=foobar",
                    "--passcode=01234",
                    "--icann_referral_email=foo@bar.test",
                    "--street=\"123 Fake St\"",
                    "--city Fakington",
                    "--state MA",
                    "--zip 00351",
                    "--cc US",
                    "--force",
                    "clientz"));
    assertThat(thrown).hasMessageThat().startsWith("Cannot add allowed TLDs");
  }

  @Test
  public void testFailure_invalidIpWhitelistFlag() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--ip_whitelist=foobarbaz",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "clientz"));
  }

  @Test
  public void testSuccess_ipWhitelistFlagWithNull() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--ip_whitelist=192.168.1.1,192.168.0.2/16,null",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "clientz"));
  }

  @Test
  public void testFailure_invalidCertFileContents() {
    assertThrows(
        Exception.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--cert_file=" + writeToTmpFile("ABCDEF"),
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "clientz"));
  }

  @Test
  public void testFailure_invalidFailoverCertFileContents() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--failover_cert_file=" + writeToTmpFile("ABCDEF"),
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "clientz"));
  }

  @Test
  public void testFailure_certHashAndCertFile() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--cert_file=" + getCertFilename(),
                "--cert_hash=ABCDEF",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "clientz"));
  }

  @Test
  public void testFailure_certHashNotBase64() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--name=blobio",
                    "--password=some_password",
                    "--registrar_type=REAL",
                    "--iana_id=8",
                    "--cert_hash=!",
                    "--passcode=01234",
                    "--icann_referral_email=foo@bar.test",
                    "--street=\"123 Fake St\"",
                    "--city Fakington",
                    "--state MA",
                    "--zip 00351",
                    "--cc US",
                    "clientz"));
    assertThat(thrown).hasMessageThat().contains("base64");
  }

  @Test
  public void testFailure_certHashNotA256BitValue() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--name=blobio",
                    "--password=some_password",
                    "--registrar_type=REAL",
                    "--iana_id=8",
                    "--cert_hash=abc",
                    "--passcode=01234",
                    "--icann_referral_email=foo@bar.test",
                    "--street=\"123 Fake St\"",
                    "--city Fakington",
                    "--state MA",
                    "--zip 00351",
                    "--cc US",
                    "clientz"));
    assertThat(thrown).hasMessageThat().contains("256");
  }

  @Test
  public void testFailure_missingName() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--password=blobio",
                    "--registrar_type=REAL",
                    "--iana_id=8",
                    "--passcode=01234",
                    "--icann_referral_email=foo@bar.test",
                    "--street=\"123 Fake St\"",
                    "--city Fakington",
                    "--state MA",
                    "--zip 00351",
                    "--cc US",
                    "clientz"));
    assertThat(thrown).hasMessageThat().contains("--name is a required field");
  }

  @Test
  public void testFailure_missingPassword() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--name=blobio",
                    "--registrar_type=REAL",
                    "--iana_id=8",
                    "--passcode=01234",
                    "--icann_referral_email=foo@bar.test",
                    "--street=\"123 Fake St\"",
                    "--city Fakington",
                    "--state MA",
                    "--zip 00351",
                    "--cc US",
                    "clientz"));
    assertThat(thrown).hasMessageThat().contains("--password is a required field");
  }

  @Test
  public void testFailure_emptyPassword() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--name=blobio",
                    "--password=\"\"",
                    "--registrar_type=REAL",
                    "--iana_id=8",
                    "--passcode=01234",
                    "--icann_referral_email=foo@bar.test",
                    "--street=\"123 Fake St\"",
                    "--city Fakington",
                    "--state MA",
                    "--zip 00351",
                    "--cc US",
                    "clientz"));
    assertThat(thrown).hasMessageThat().contains("--password is a required field");
  }

  @Test
  public void testFailure_clientIdTooShort() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "ab"));
  }

  @Test
  public void testFailure_clientIdTooLong() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "clientabcdefghijk"));
  }

  @Test
  public void testFailure_missingClientId() {
    assertThrows(
        ParameterException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "--force"));
  }

  @Test
  public void testFailure_missingStreetLines() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--city Brooklyn",
                "--state NY",
                "--zip 11223",
                "--cc US",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "clientz"));
  }

  @Test
  public void testFailure_missingCity() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--street=\"1234 Main St\"",
                "--street \"4th Floor\"",
                "--street \"Suite 1\"",
                "--state NY",
                "--zip 11223",
                "--cc US",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "clientz"));
  }


  @Test
  public void testFailure_missingState() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--street=\"1234 Main St\"",
                "--street \"4th Floor\"",
                "--street \"Suite 1\"",
                "--city Brooklyn",
                "--zip 11223",
                "--cc US",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "clientz"));
  }

  @Test
  public void testFailure_missingZip() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--street=\"1234 Main St\"",
                "--street \"4th Floor\"",
                "--street \"Suite 1\"",
                "--city Brooklyn",
                "--state NY",
                "--cc US",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "clientz"));
  }

  @Test
  public void testFailure_missingCc() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--street=\"1234 Main St\"",
                "--street \"4th Floor\"",
                "--street \"Suite 1\"",
                "--city Brooklyn",
                "--state NY",
                "--zip 11223",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "clientz"));
  }

  @Test
  public void testFailure_invalidCc() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--street=\"1234 Main St\"",
                "--street \"4th Floor\"",
                "--street \"Suite 1\"",
                "--city Brooklyn",
                "--state NY",
                "--zip 11223",
                "--cc USA",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "clientz"));
  }

  @Test
  public void testFailure_tooManyStreetLines() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--street=\"Attn:Hey You Guys\"",
                "--street=\"1234 Main St\"",
                "--street \"4th Floor\"",
                "--street \"Suite 1\"",
                "--city Brooklyn",
                "--state NY",
                "--zip 11223",
                "--cc US",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "clientz"));
  }

  @Test
  public void testFailure_tooFewStreetLines() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--street",
                "--city Brooklyn",
                "--state NY",
                "--zip 11223",
                "--cc US",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "clientz"));
  }

  @Test
  public void testFailure_missingIanaIdForRealRegistrar() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "clientz"));
  }

  @Test
  public void testFailure_negativeIanaId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=-1",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "clientz"));
  }

  @Test
  public void testFailure_nonIntegerIanaId() {
    assertThrows(
        ParameterException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=ABC12345",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "clientz"));
  }

  @Test
  public void testFailure_negativeBillingId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--billing_id=-1",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "clientz"));
  }

  @Test
  public void testFailure_nonIntegerBillingId() {
    assertThrows(
        ParameterException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--billing_id=ABC12345",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "clientz"));
  }

  @Test
  public void testFailure_missingPhonePasscode() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "clientz"));
  }

  @Test
  public void testFailure_missingIcannReferralEmail() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--name=blobio",
                    "--password=some_password",
                    "--registrar_type=REAL",
                    "--iana_id=8",
                    "--passcode=01234",
                    "--street=\"123 Fake St\"",
                    "--city Fakington",
                    "--state MA",
                    "--zip 00351",
                    "--cc US",
                    "clientz"));
    assertThat(thrown).hasMessageThat().contains("--icann_referral_email");
  }

  @Test
  public void testFailure_passcodeTooShort() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--passcode=0123",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "clientz"));
  }

  @Test
  public void testFailure_passcodeTooLong() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--passcode=0123",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "clientz"));
  }

  @Test
  public void testFailure_invalidPasscode() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--passcode=code1",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "clientz"));
  }

  @Test
  public void testFailure_twoClientsSpecified() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "ClientY",
                "clientz"));
  }

  @Test
  public void testFailure_unknownFlag() {
    assertThrows(
        ParameterException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--unrecognized_flag=foo",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "clientz"));
  }

  @Test
  public void testFailure_alreadyExists() {
    persistNewRegistrar("existing", "Existing Registrar", Registrar.Type.REAL, 1L);
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                runCommandForced(
                    "--name=blobio",
                    "--password=some_password",
                    "--registrar_type=REAL",
                    "--iana_id=8",
                    "--passcode=01234",
                    "--icann_referral_email=foo@bar.test",
                    "--street=\"123 Fake St\"",
                    "--city Fakington",
                    "--state MA",
                    "--zip 00351",
                    "--cc US",
                    "existing"));
    assertThat(thrown).hasMessageThat().contains("Registrar existing already exists");
  }

  @Test
  public void testFailure_registrarNameSimilarToExisting() {
    // Note that "tHeRe GiStRaR" normalizes identically to "The Registrar", which is created by
    // AppEngineRule.
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--name=tHeRe GiStRaR",
                    "--password=some_password",
                    "--registrar_type=REAL",
                    "--iana_id=8",
                    "--passcode=01234",
                    "--icann_referral_email=foo@bar.test",
                    "--street=\"123 Fake St\"",
                    "--city Fakington",
                    "--state MA",
                    "--zip 00351",
                    "--cc US",
                    "clientz"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "The registrar name tHeRe GiStRaR normalizes "
                + "identically to existing registrar name The Registrar");
  }

  @Test
  public void testFailure_clientIdNormalizesToExisting() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--name=blahhh",
                    "--password=some_password",
                    "--registrar_type=REAL",
                    "--iana_id=8",
                    "--passcode=01234",
                    "--icann_referral_email=foo@bar.test",
                    "--street=\"123 Fake St\"",
                    "--city Fakington",
                    "--state MA",
                    "--zip 00351",
                    "--cc US",
                    "theregistrar"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "The registrar client identifier theregistrar "
                + "normalizes identically to existing registrar TheRegistrar");
  }

  @Test
  public void testFailure_clientIdIsInvalidFormat() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--name=blahhh",
                    "--password=some_password",
                    "--registrar_type=REAL",
                    "--iana_id=8",
                    "--passcode=01234",
                    "--icann_referral_email=foo@bar.test",
                    "--street=\"123 Fake St\"",
                    "--city Fakington",
                    "--state MA",
                    "--zip 00351",
                    "--cc US",
                    ".L33T"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "Client identifier (.L33T) can only contain lowercase letters, numbers, and hyphens");
  }

  @Test
  public void testFailure_phone() {
    assertThrows(
        ParameterException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--phone=3",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "clientz"));
  }

  @Test
  public void testFailure_fax() {
    assertThrows(
        ParameterException.class,
        () ->
            runCommandForced(
                "--name=blobio",
                "--password=some_password",
                "--registrar_type=REAL",
                "--iana_id=8",
                "--fax=3",
                "--passcode=01234",
                "--icann_referral_email=foo@bar.test",
                "--street=\"123 Fake St\"",
                "--city Fakington",
                "--state MA",
                "--zip 00351",
                "--cc US",
                "clientz"));
  }

  @Test
  public void testFailure_badEmail() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--name=blobio",
                    "--password=some_password",
                    "--registrar_type=REAL",
                    "--iana_id=8",
                    "--passcode=01234",
                    "--icann_referral_email=lolcat",
                    "--street=\"123 Fake St\"",
                    "--city Fakington",
                    "--state MA",
                    "--zip 00351",
                    "--cc US",
                    "clientz"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Provided email lolcat is not a valid email address");
  }
}
