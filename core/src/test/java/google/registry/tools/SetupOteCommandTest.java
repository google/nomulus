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
import static google.registry.model.registrar.Registrar.State.ACTIVE;
import static google.registry.model.registry.Registry.TldState.GENERAL_AVAILABILITY;
import static google.registry.model.registry.Registry.TldState.START_DATE_SUNRISE;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT_HASH;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.persistPremiumList;
import static google.registry.testing.DatastoreHelper.persistResource;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.Assert.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldState;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.FakeClock;
import google.registry.util.CidrAddressBlock;
import java.security.cert.CertificateParsingException;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link SetupOteCommand}. */
public class SetupOteCommandTest extends CommandTestCase<SetupOteCommand> {

  static final String PASSWORD = "abcdefghijklmnop";

  DeterministicStringGenerator passwordGenerator =
      new DeterministicStringGenerator("abcdefghijklmnopqrstuvwxyz");

  @Before
  public void init() {
    command.passwordGenerator = passwordGenerator;
    command.clock = new FakeClock(DateTime.parse("2018-07-07TZ"));
    persistPremiumList("default_sandbox_list", "sandbox,USD 1000");
  }

  /** Verify TLD creation. */
  private void verifyTldCreation(
      String tldName,
      String roidSuffix,
      TldState tldState,
      boolean isEarlyAccess) {
    Registry registry = Registry.get(tldName);
    assertThat(registry).isNotNull();
    assertThat(registry.getRoidSuffix()).isEqualTo(roidSuffix);
    assertThat(registry.getTldState(DateTime.now(UTC))).isEqualTo(tldState);
    assertThat(registry.getDnsWriters()).containsExactly("VoidDnsWriter");
    assertThat(registry.getPremiumList()).isNotNull();
    assertThat(registry.getPremiumList().getName()).isEqualTo("default_sandbox_list");
    assertThat(registry.getAddGracePeriodLength()).isEqualTo(Duration.standardMinutes(60));
    assertThat(registry.getRedemptionGracePeriodLength()).isEqualTo(Duration.standardMinutes(10));
    assertThat(registry.getPendingDeleteLength()).isEqualTo(Duration.standardMinutes(5));
    ImmutableSortedMap<DateTime, Money> eapFeeSchedule = registry.getEapFeeScheduleAsMap();
    if (!isEarlyAccess) {
      assertThat(eapFeeSchedule)
          .isEqualTo(ImmutableSortedMap.of(new DateTime(0), Money.of(CurrencyUnit.USD, 0)));
    } else {
      assertThat(eapFeeSchedule)
          .isEqualTo(
              ImmutableSortedMap.of(
                  new DateTime(0),
                  Money.of(CurrencyUnit.USD, 0),
                  DateTime.parse("2018-03-01T00:00:00Z"),
                  Money.of(CurrencyUnit.USD, 100),
                  DateTime.parse("2030-03-01T00:00:00Z"),
                  Money.of(CurrencyUnit.USD, 0)));
    }
  }

  private void verifyRegistrarCreation(
      String registrarName,
      String allowedTld,
      String password,
      ImmutableList<CidrAddressBlock> ipWhitelist,
      boolean hashOnly) {
    Registrar registrar = loadRegistrar(registrarName);
    assertThat(registrar).isNotNull();
    assertThat(registrar.getAllowedTlds()).containsExactlyElementsIn(ImmutableSet.of(allowedTld));
    assertThat(registrar.getRegistrarName()).isEqualTo(registrarName);
    assertThat(registrar.getState()).isEqualTo(ACTIVE);
    assertThat(registrar.verifyPassword(password)).isTrue();
    assertThat(registrar.getIpAddressWhitelist()).isEqualTo(ipWhitelist);
    assertThat(registrar.getClientCertificateHash()).isEqualTo(SAMPLE_CERT_HASH);
    // If certificate hash is provided, there's no certificate file stored with the registrar.
    if (!hashOnly) {
      assertThat(registrar.getClientCertificate()).isEqualTo(SAMPLE_CERT);
    }
  }

  private void verifyRegistrarCreation(
      String registrarName,
      String allowedTld,
      String password,
      ImmutableList<CidrAddressBlock> ipWhitelist) {
    verifyRegistrarCreation(registrarName, allowedTld, password, ipWhitelist, false);
  }

  private void verifyRegistrarContactCreation(String registrarName, String email) {
    ImmutableSet<RegistrarContact> registrarContacts =
        loadRegistrar(registrarName).getContacts();
    assertThat(registrarContacts).hasSize(1);
    RegistrarContact registrarContact = registrarContacts.stream().findAny().get();
    assertThat(registrarContact.getEmailAddress()).isEqualTo(email);
    assertThat(registrarContact.getName()).isEqualTo(email);
    assertThat(registrarContact.getGaeUserId()).isNotNull();
  }

  @Test
  public void testSuccess() throws Exception {
    runCommandForced(
        "--ip_whitelist=1.1.1.1",
        "--registrar=blobio",
        "--email=contact@email.com",
        "--certfile=" + getCertFilename());

    verifyTldCreation("blobio-sunrise", "BLOBIOS0", START_DATE_SUNRISE, false);
    verifyTldCreation("blobio-ga", "BLOBIOG2", GENERAL_AVAILABILITY, false);
    verifyTldCreation("blobio-eap", "BLOBIOE3", GENERAL_AVAILABILITY, true);

    ImmutableList<CidrAddressBlock> ipAddress = ImmutableList.of(
        CidrAddressBlock.create("1.1.1.1"));

    verifyRegistrarCreation("blobio-1", "blobio-sunrise", PASSWORD, ipAddress);
    verifyRegistrarCreation("blobio-3", "blobio-ga", PASSWORD, ipAddress);
    verifyRegistrarCreation("blobio-4", "blobio-ga", PASSWORD, ipAddress);
    verifyRegistrarCreation("blobio-5", "blobio-eap", PASSWORD, ipAddress);

    verifyRegistrarContactCreation("blobio-1", "contact@email.com");
    verifyRegistrarContactCreation("blobio-3", "contact@email.com");
    verifyRegistrarContactCreation("blobio-4", "contact@email.com");
    verifyRegistrarContactCreation("blobio-5", "contact@email.com");
  }

  @Test
  public void testSuccess_shortRegistrarName() throws Exception {
    runCommandForced(
        "--ip_whitelist=1.1.1.1",
        "--registrar=abc",
        "--email=abc@email.com",
        "--certfile=" + getCertFilename());

    verifyTldCreation("abc-sunrise", "ABCSUNR0", START_DATE_SUNRISE, false);
    verifyTldCreation("abc-ga", "ABCGA2", GENERAL_AVAILABILITY, false);
    verifyTldCreation("abc-eap", "ABCEAP3", GENERAL_AVAILABILITY, true);

    ImmutableList<CidrAddressBlock> ipAddress =
        ImmutableList.of(CidrAddressBlock.create("1.1.1.1"));

    verifyRegistrarCreation("abc-1", "abc-sunrise", PASSWORD, ipAddress);
    verifyRegistrarCreation("abc-3", "abc-ga", PASSWORD, ipAddress);
    verifyRegistrarCreation("abc-4", "abc-ga", PASSWORD, ipAddress);
    verifyRegistrarCreation("abc-5", "abc-eap", PASSWORD, ipAddress);

    verifyRegistrarContactCreation("abc-1", "abc@email.com");
    verifyRegistrarContactCreation("abc-3", "abc@email.com");
    verifyRegistrarContactCreation("abc-4", "abc@email.com");
    verifyRegistrarContactCreation("abc-5", "abc@email.com");
  }

  @Test
  public void testSuccess_certificateHash() throws Exception {
    runCommandForced(
        "--ip_whitelist=1.1.1.1",
        "--registrar=blobio",
        "--email=contact@email.com",
        "--certhash=" + SAMPLE_CERT_HASH);

    verifyTldCreation("blobio-eap", "BLOBIOE3", GENERAL_AVAILABILITY, true);

    ImmutableList<CidrAddressBlock> ipAddress =
        ImmutableList.of(CidrAddressBlock.create("1.1.1.1"));

    verifyRegistrarCreation("blobio-5", "blobio-eap", PASSWORD, ipAddress, true);

    verifyRegistrarContactCreation("blobio-5", "contact@email.com");
  }

  @Test
  public void testSuccess_multipleIps() throws Exception {
    runCommandForced(
        "--ip_whitelist=1.1.1.1,2.2.2.2",
        "--registrar=blobio",
        "--email=contact@email.com",
        "--certfile=" + getCertFilename());

    verifyTldCreation("blobio-sunrise", "BLOBIOS0", START_DATE_SUNRISE, false);
    verifyTldCreation("blobio-ga", "BLOBIOG2", GENERAL_AVAILABILITY, false);
    verifyTldCreation("blobio-eap", "BLOBIOE3", GENERAL_AVAILABILITY, true);

    ImmutableList<CidrAddressBlock> ipAddresses = ImmutableList.of(
        CidrAddressBlock.create("1.1.1.1"),
        CidrAddressBlock.create("2.2.2.2"));

    verifyRegistrarCreation("blobio-1", "blobio-sunrise", PASSWORD, ipAddresses);
    verifyRegistrarCreation("blobio-3", "blobio-ga", PASSWORD, ipAddresses);
    verifyRegistrarCreation("blobio-4", "blobio-ga", PASSWORD, ipAddresses);
    verifyRegistrarCreation("blobio-5", "blobio-eap", PASSWORD, ipAddresses);

    verifyRegistrarContactCreation("blobio-1", "contact@email.com");
    verifyRegistrarContactCreation("blobio-3", "contact@email.com");
    verifyRegistrarContactCreation("blobio-4", "contact@email.com");
    verifyRegistrarContactCreation("blobio-5", "contact@email.com");
  }

  @Test
  public void testFailure_missingIpWhitelist() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    "--registrar=blobio",
                    "--email=contact@email.com",
                    "--certfile=" + getCertFilename()));
    assertThat(thrown).hasMessageThat().contains("option is required: -w, --ip_whitelist");
  }

  @Test
  public void testFailure_missingRegistrar() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    "--ip_whitelist=1.1.1.1",
                    "--email=contact@email.com",
                    "--certfile=" + getCertFilename()));
    assertThat(thrown).hasMessageThat().contains("option is required: -r, --registrar");
  }

  @Test
  public void testFailure_missingCertificateFileAndCertificateHash() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--ip_whitelist=1.1.1.1",
                    "--email=contact@email.com",
                    "--registrar=blobio"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "Must specify exactly one of client certificate file or client certificate hash.");
  }

  @Test
  public void testFailure_suppliedCertificateFileAndCertificateHash() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--ip_whitelist=1.1.1.1",
                    "--email=contact@email.com",
                    "--registrar=blobio",
                    "--certfile=" + getCertFilename(),
                    "--certhash=" + SAMPLE_CERT_HASH));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "Must specify exactly one of client certificate file or client certificate hash.");
  }

  @Test
  public void testFailure_missingEmail() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    "--ip_whitelist=1.1.1.1",
                    "--certfile=" + getCertFilename(),
                    "--registrar=blobio"));
    assertThat(thrown).hasMessageThat().contains("option is required: --email");
  }

  @Test
  public void testFailure_invalidCert() {
    CertificateParsingException thrown =
        assertThrows(
            CertificateParsingException.class,
            () ->
                runCommandForced(
                    "--ip_whitelist=1.1.1.1",
                    "--registrar=blobio",
                    "--email=contact@email.com",
                    "--certfile=/dev/null"));
    assertThat(thrown).hasMessageThat().contains("No X509Certificate found");
  }

  @Test
  public void testFailure_invalidRegistrar() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--ip_whitelist=1.1.1.1",
                    "--registrar=3blo-bio",
                    "--email=contact@email.com",
                    "--certfile=" + getCertFilename()));
    assertThat(thrown).hasMessageThat().contains("Invalid registrar name: 3blo-bio");
  }

  @Test
  public void testFailure_registrarTooShort() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--ip_whitelist=1.1.1.1",
                    "--registrar=bl",
                    "--email=contact@email.com",
                    "--certfile=" + getCertFilename()));
    assertThat(thrown).hasMessageThat().contains("Invalid registrar name: bl");
  }

  @Test
  public void testFailure_registrarTooLong() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--ip_whitelist=1.1.1.1",
                    "--registrar=blobiotoooolong",
                    "--email=contact@email.com",
                    "--certfile=" + getCertFilename()));
    assertThat(thrown).hasMessageThat().contains("Invalid registrar name: blobiotoooolong");
  }

  @Test
  public void testFailure_registrarInvalidCharacter() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--ip_whitelist=1.1.1.1",
                    "--registrar=blo#bio",
                    "--email=contact@email.com",
                    "--certfile=" + getCertFilename()));
    assertThat(thrown).hasMessageThat().contains("Invalid registrar name: blo#bio");
  }

  @Test
  public void testFailure_tldExists() {
    createTld("blobio-sunrise");
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                runCommandForced(
                    "--ip_whitelist=1.1.1.1",
                    "--registrar=blobio",
                    "--email=contact@email.com",
                    "--certfile=" + getCertFilename()));
    assertThat(thrown).hasMessageThat().contains("Registry(\"blobio-sunrise\")");
  }

  @Test
  public void testSuccess_tldExists_replaceExisting() throws Exception {
    createTld("blobio-sunrise");

    runCommandForced(
        "--overwrite",
        "--ip_whitelist=1.1.1.1",
        "--registrar=blobio",
        "--email=contact@email.com",
        "--certfile=" + getCertFilename());

    verifyTldCreation("blobio-sunrise", "BLOBIOS0", START_DATE_SUNRISE, false);
    verifyTldCreation("blobio-ga", "BLOBIOG2", GENERAL_AVAILABILITY, false);
  }

  @Test
  public void testFailure_registrarExists() {
    Registrar registrar = loadRegistrar("TheRegistrar").asBuilder()
        .setClientId("blobio-1")
        .setRegistrarName("blobio-1")
        .build();
    persistResource(registrar);
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                runCommandForced(
                    "--ip_whitelist=1.1.1.1",
                    "--registrar=blobio",
                    "--email=contact@email.com",
                    "--certfile=" + getCertFilename()));
    assertThat(thrown).hasMessageThat().contains("Registrar(\"blobio-1\")");
  }

  @Test
  public void testSuccess_registrarExists_replaceExisting() throws Exception {
    Registrar registrar = loadRegistrar("TheRegistrar").asBuilder()
        .setClientId("blobio-1")
        .setRegistrarName("blobio-1")
        .build();
    persistResource(registrar);

    runCommandForced(
        "--overwrite",
        "--ip_whitelist=1.1.1.1",
        "--registrar=blobio",
        "--email=contact@email.com",
        "--certfile=" + getCertFilename());

    ImmutableList<CidrAddressBlock> ipAddress = ImmutableList.of(
        CidrAddressBlock.create("1.1.1.1"));

    verifyRegistrarCreation("blobio-1", "blobio-sunrise", PASSWORD, ipAddress);
    verifyRegistrarCreation("blobio-3", "blobio-ga", PASSWORD, ipAddress);
  }
}
