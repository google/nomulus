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

import static google.registry.testing.CertificateSamples.SAMPLE_CERT3_HASH;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.X509Utils.getCertificateHash;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.flows.certs.CertificateChecker;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.CertificateSamples;
import google.registry.testing.SystemPropertyExtension;
import google.registry.util.SelfSignedCaCertificate;
import java.io.StringWriter;
import java.security.cert.X509Certificate;
import java.util.Optional;
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

  void setCredentials(String clientCertificateHash) {
    setTransportCredentials(
        new TlsCredentials(
            true,
            Optional.ofNullable(clientCertificateHash),
            Optional.of("192.168.1.100:54321"),
            certificateChecker));
  }

  @BeforeEach
  void beforeEach() {
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
  }

  @Test
  void testLoginLogout() throws Exception {
    setCredentials(SAMPLE_CERT3_HASH);
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    assertThatLogoutSucceeds();
  }

  @Test
  void testLogin_wrongPasswordFails() throws Exception {
    setCredentials(SAMPLE_CERT3_HASH);
    // For TLS login, we also check the epp xml password.
    assertThatLogin("NewRegistrar", "incorrect")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of("CODE", "2200", "MSG", "Registrar password is incorrect"));
  }

  @Test
  void testMultiLogin() throws Exception {
    setCredentials(SAMPLE_CERT3_HASH);
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
    setCredentials(SAMPLE_CERT3_HASH);
    assertThatLogin("TheRegistrar", "password2")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2200", "MSG", "Registrar certificate does not match stored certificate"));
  }

  @Test
  void testBadCertificate_failsBadCertificate2200() throws Exception {
    setCredentials("laffo");
    assertThatLogin("NewRegistrar", "foo-BAR2")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2200", "MSG", "Registrar certificate does not match stored certificate"));
  }

  @Test
  void testGfeDidntProvideClientCertificate_failsMissingCertificate2200() throws Exception {
    setCredentials(null);
    assertThatLogin("NewRegistrar", "foo-BAR2")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of("CODE", "2200", "MSG", "Registrar certificate not present"));
  }

  @Test
  void testGoodPrimaryCertificate() throws Exception {
    setCredentials(SAMPLE_CERT3_HASH);
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
    setCredentials(SAMPLE_CERT3_HASH);
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
    setCredentials(SAMPLE_CERT3_HASH);
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
    setCredentials("laffo");
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
    // SAMPLE_CERT has a validity period that is too long
    setCredentials(CertificateSamples.SAMPLE_CERT_HASH);
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

    // SAMPLE_CERT has a validity period that is too long
    setCredentials(getCertificateHash(certificate));
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
}
