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

package google.registry.flows;

import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.LogsSubject.assertAboutLogs;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.X509Utils.encodeX509Certificate;
import static google.registry.util.X509Utils.encodeX509CertificateFromPemString;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.testing.TestLogHandler;
import google.registry.config.RegistryEnvironment;
import google.registry.flows.certs.CertificateChecker;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.CertificateSamples;
import google.registry.testing.SystemPropertyExtension;
import google.registry.util.SelfSignedCaCertificate;
import java.io.StringWriter;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.shaded.org.bouncycastle.openssl.jcajce.JcaMiscPEMGenerator;
import org.testcontainers.shaded.org.bouncycastle.util.io.pem.PemObjectGenerator;
import org.testcontainers.shaded.org.bouncycastle.util.io.pem.PemWriter;

/** Test logging in with TLS credentials. */
class EppLoginTlsTest extends EppTestCase {

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @RegisterExtension
  @Order(value = Integer.MAX_VALUE)
  final SystemPropertyExtension systemPropertyExtension = new SystemPropertyExtension();

  private final CertificateChecker certificateChecker =
      new CertificateChecker(
          ImmutableSortedMap.of(START_OF_TIME, 825, DateTime.parse("2020-09-01T00:00:00Z"), 398),
          30,
          2048,
          ImmutableSet.of("secp256r1", "secp384r1"),
          clock);

  private final Logger loggerToIntercept =
      Logger.getLogger(TlsCredentials.class.getCanonicalName());
  private final TestLogHandler handler = new TestLogHandler();

  private String encodedCertString;

  void setCredentials(String clientCertificateHash, String clientCertificate) {
    setTransportCredentials(
        new TlsCredentials(
            true,
            Optional.ofNullable(clientCertificateHash),
            Optional.ofNullable(clientCertificate),
            Optional.of("192.168.1.100:54321"),
            certificateChecker));
  }

  @BeforeEach
  void beforeEach() throws CertificateException {
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setClientCertificate(CertificateSamples.SAMPLE_CERT3, DateTime.now(UTC))
            .build());
    // Set a cert for the second registrar, or else any cert will be allowed for login.
    persistResource(
        loadRegistrar("TheRegistrar")
            .asBuilder()
            .setClientCertificate(CertificateSamples.SAMPLE_CERT2, DateTime.now(UTC))
            .build());
    loggerToIntercept.addHandler(handler);
    encodedCertString = encodeX509CertificateFromPemString(CertificateSamples.SAMPLE_CERT3);
  }

  @Test
  void testLoginLogout() throws Exception {
    setCredentials(null, encodedCertString);
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    assertThatLogoutSucceeds();
  }

  @Test
  void testLogin_wrongPasswordFails() throws Exception {
    setCredentials(CertificateSamples.SAMPLE_CERT3_HASH, encodedCertString);
    // For TLS login, we also check the epp xml password.
    assertThatLogin("NewRegistrar", "incorrect")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of("CODE", "2200", "MSG", "Registrar password is incorrect"));
  }

  @Test
  void testMultiLogin() throws Exception {
    setCredentials(CertificateSamples.SAMPLE_CERT3_HASH, encodedCertString);
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    assertThatLogoutSucceeds();
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    assertThatLogoutSucceeds();
    assertThatLogin("TheRegistrar", "password2")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2200", "MSG", "Registrar certificate does not match stored certificate"));
  }

  @Test
  void testNonAuthedLogin_fails() throws Exception {
    setCredentials(CertificateSamples.SAMPLE_CERT3_HASH, encodedCertString);
    assertThatLogin("TheRegistrar", "password2")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2200", "MSG", "Registrar certificate does not match stored certificate"));
  }

  @Test
  void testBadCertificate_failsBadCertificate2200() throws Exception {
    setCredentials("laffo", "cert");
    assertThatLogin("NewRegistrar", "foo-BAR2")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2200", "MSG", "Registrar certificate does not match stored certificate"));
  }

  @Test
  void testGfeDidntProvideClientCertificate_failsMissingCertificate2200() throws Exception {
    setCredentials(null, null);
    assertThatLogin("NewRegistrar", "foo-BAR2")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of("CODE", "2200", "MSG", "Registrar certificate not present"));
  }

  @Test
  void testGoodPrimaryCertificate() throws Exception {
    setCredentials(null, encodedCertString);
    DateTime now = DateTime.now(UTC);
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setClientCertificate(CertificateSamples.SAMPLE_CERT3, now)
            .setFailoverClientCertificate(CertificateSamples.SAMPLE_CERT2, now)
            .build());
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
  }

  @Test
  void testGoodFailoverCertificate() throws Exception {
    setCredentials(null, encodedCertString);
    DateTime now = DateTime.now(UTC);
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setClientCertificate(CertificateSamples.SAMPLE_CERT, now)
            .setFailoverClientCertificate(CertificateSamples.SAMPLE_CERT3, now)
            .build());
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
  }

  @Test
  void testMissingPrimaryCertificateButHasFailover_usesFailover() throws Exception {
    setCredentials(null, encodedCertString);
    DateTime now = DateTime.now(UTC);
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setClientCertificate(null, now)
            .setFailoverClientCertificate(CertificateSamples.SAMPLE_CERT3, now)
            .build());
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
  }

  @Test
  void testRegistrarHasNoCertificatesOnFile_fails() throws Exception {
    setCredentials("laffo", "cert");
    DateTime now = DateTime.now(UTC);
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setClientCertificate(null, now)
            .setFailoverClientCertificate(null, now)
            .build());
    assertThatLogin("NewRegistrar", "foo-BAR2")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of("CODE", "2200", "MSG", "Registrar certificate is not configured"));
  }

  @Test
  void testCertificateDoesNotMeetRequirements_fails() throws Exception {
    String proxyEncoded = encodeX509CertificateFromPemString(CertificateSamples.SAMPLE_CERT);
    // SAMPLE_CERT has a validity period that is too long
    setCredentials(CertificateSamples.SAMPLE_CERT_HASH, proxyEncoded);
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setClientCertificate(CertificateSamples.SAMPLE_CERT, clock.nowUtc())
            .setFailoverClientCertificate(CertificateSamples.SAMPLE_CERT2, clock.nowUtc())
            .build());
    assertThatLogin("NewRegistrar", "foo-BAR2")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE",
                "2200",
                "MSG",
                "Registrar certificate contains the following security violations:\n"
                    + "Certificate validity period is too long; it must be less than or equal to"
                    + " 398 days."));
  }

  @Test
  void testCertificateDoesNotMeetMultipleRequirements_fails() throws Exception {
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                "test", clock.nowUtc().plusDays(100), clock.nowUtc().plusDays(5000))
            .cert();

    StringWriter sw = new StringWriter();
    try (PemWriter pw = new PemWriter(sw)) {
      PemObjectGenerator generator = new JcaMiscPEMGenerator(certificate);
      pw.writeObject(generator);
    }

    String proxyEncoded = encodeX509Certificate(certificate);

    // SAMPLE_CERT has a validity period that is too long
    setCredentials(null, proxyEncoded);
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setClientCertificate(sw.toString(), clock.nowUtc())
            .setFailoverClientCertificate(CertificateSamples.SAMPLE_CERT2, clock.nowUtc())
            .build());
    assertThatLogin("NewRegistrar", "foo-BAR2")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE",
                "2200",
                "MSG",
                "Registrar certificate contains the following security violations:\n"
                    + "Certificate is expired.\n"
                    + "Certificate validity period is too long; it must be less than or equal to"
                    + " 398 days."));
  }

  @Test
  // TODO(sarahbot@): Remove this test once requirements are enforced in production
  void testCertificateDoesNotMeetRequirementsInProduction_succeeds() throws Exception {
    RegistryEnvironment.PRODUCTION.setup(systemPropertyExtension);
    // SAMPLE_CERT has a validity period that is too long
    String proxyEncoded = encodeX509CertificateFromPemString(CertificateSamples.SAMPLE_CERT);
    setCredentials(null, proxyEncoded);
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setClientCertificate(CertificateSamples.SAMPLE_CERT, clock.nowUtc())
            .setFailoverClientCertificate(CertificateSamples.SAMPLE_CERT2, clock.nowUtc())
            .build());
    // Even though the certificate contains security violations, the login will succeed in
    // production
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    assertAboutLogs()
        .that(handler)
        .hasLogAtLevelWithMessage(
            Level.WARNING,
            "Registrar certificate used for NewRegistrar does not meet certificate requirements:"
                + " Certificate validity period is too long; it must be less than or equal to 398"
                + " days.");
  }

  @Test
  void testRegistrarCertificateContainsExtraMetadata_succeeds() throws Exception {
    String certPem =
        String.format(
            "Bag Attributes\n"
                + "    localKeyID: 1F 1C 3A 3A 4C 03 EC C4 BC 7A C3 21 A9 F2 13 66 21 B8 7B 26 \n"
                + "subject=/C=US/ST=New York/L=New"
                + " York/O=Test/OU=ABC/CN=tester.test/emailAddress=test-certificate@test.test\n"
                + "issuer=/C=US/ST=NY/L=NYC/O=ABC/OU=TEST CA/CN=TEST"
                + " CA/emailAddress=testing@test.test\n"
                + "%s",
            CertificateSamples.SAMPLE_CERT3);

    setCredentials(null, encodeX509CertificateFromPemString(certPem));
    DateTime now = DateTime.now(UTC);
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setClientCertificate(certPem, now)
            .setFailoverClientCertificate(CertificateSamples.SAMPLE_CERT2, now)
            .build());
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
  }

  @Test
  void testRegistrarCertificateContainsExtraMetadataAndViolations_fails() throws Exception {
    String certPem =
        String.format(
            "Bag Attributes\n"
                + "    localKeyID: 1F 1C 3A 3A 4C 03 EC C4 BC 7A C3 21 A9 F2 13 66 21 B8 7B 26 \n"
                + "subject=/C=US/ST=New York/L=New"
                + " York/O=Test/OU=ABC/CN=tester.test/emailAddress=test-certificate@test.test\n"
                + "issuer=/C=US/ST=NY/L=NYC/O=ABC/OU=TEST CA/CN=TEST"
                + " CA/emailAddress=testing@test.test\n"
                + "%s",
            CertificateSamples.SAMPLE_CERT);

    setCredentials(null, encodeX509CertificateFromPemString(certPem));
    DateTime now = DateTime.now(UTC);
    persistResource(
        loadRegistrar("NewRegistrar")
            .asBuilder()
            .setClientCertificate(certPem, now)
            .setFailoverClientCertificate(CertificateSamples.SAMPLE_CERT2, now)
            .build());
    assertThatLogin("NewRegistrar", "foo-BAR2")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE",
                "2200",
                "MSG",
                "Registrar certificate contains the following security violations:\n"
                    + "Certificate validity period is too long; it must be less than or equal to"
                    + " 398 days."));
  }
}
