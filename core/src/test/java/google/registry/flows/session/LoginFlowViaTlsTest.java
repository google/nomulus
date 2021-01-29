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

package google.registry.flows.session;

import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.X509Utils.encodeX509CertificateFromPemString;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.net.InetAddresses;
import google.registry.flows.TlsCredentials;
import google.registry.flows.TlsCredentials.BadRegistrarCertificateException;
import google.registry.flows.TlsCredentials.BadRegistrarIpAddressException;
import google.registry.flows.TlsCredentials.MissingRegistrarCertificateException;
import google.registry.flows.certs.CertificateChecker;
import google.registry.model.registrar.Registrar;
import google.registry.testing.CertificateSamples;
import google.registry.util.CidrAddressBlock;
import google.registry.util.SelfSignedCaCertificate;
import java.io.StringWriter;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.org.bouncycastle.openssl.jcajce.JcaMiscPEMGenerator;
import org.testcontainers.shaded.org.bouncycastle.util.io.pem.PemObjectGenerator;
import org.testcontainers.shaded.org.bouncycastle.util.io.pem.PemWriter;

/** Unit tests for {@link LoginFlow} when accessed via a TLS transport. */
public class LoginFlowViaTlsTest extends LoginFlowTestCase {

  private static final Optional<String> GOOD_CERT = Optional.of(CertificateSamples.SAMPLE_CERT3);
  private static final Optional<String> GOOD_CERT_HASH =
      Optional.of(CertificateSamples.SAMPLE_CERT3_HASH);
  private static final Optional<String> BAD_CERT = Optional.of(CertificateSamples.SAMPLE_CERT2);
  private static final Optional<String> BAD_CERT_HASH =
      Optional.of(CertificateSamples.SAMPLE_CERT2_HASH);
  private static final Optional<String> GOOD_IP = Optional.of("192.168.1.1");
  private static final Optional<String> BAD_IP = Optional.of("1.1.1.1");
  private static final Optional<String> GOOD_IPV6 = Optional.of("2001:db8::1");
  private static final Optional<String> BAD_IPV6 = Optional.of("2001:db8::2");
  private final CertificateChecker certificateChecker =
      new CertificateChecker(
          ImmutableSortedMap.of(START_OF_TIME, 825, DateTime.parse("2020-09-01T00:00:00Z"), 398),
          30,
          2048,
          ImmutableSet.of("secp256r1", "secp384r1"),
          clock);
  private Optional<String> encodedCertString;

  @BeforeEach
  void beforeEach() throws CertificateException {
    encodedCertString = Optional.of(encodeX509CertificateFromPemString(GOOD_CERT.get()));
  }

  @Override
  protected Registrar.Builder getRegistrarBuilder() {
    return super.getRegistrarBuilder()
        .setClientCertificate(GOOD_CERT.get(), DateTime.now(UTC))
        .setIpAddressAllowList(
            ImmutableList.of(CidrAddressBlock.create(InetAddresses.forString(GOOD_IP.get()), 32)));
  }

  @Test
  void testSuccess_withGoodCredentials() throws Exception {
    persistResource(getRegistrarBuilder().build());
    credentials =
        new TlsCredentials(true, GOOD_CERT_HASH, encodedCertString, GOOD_IP, certificateChecker);
    doSuccessfulTest("login_valid.xml");
  }

  @Test
  void testSuccess_withNewlyConstructedCertificate() throws Exception {
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                "test", clock.nowUtc().minusDays(100), clock.nowUtc().plusDays(150))
            .cert();

    StringWriter sw = new StringWriter();
    try (PemWriter pw = new PemWriter(sw)) {
      PemObjectGenerator generator = new JcaMiscPEMGenerator(certificate);
      pw.writeObject(generator);
    }

    persistResource(
        super.getRegistrarBuilder()
            .setClientCertificate(sw.toString(), DateTime.now(UTC))
            .setIpAddressAllowList(
                ImmutableList.of(
                    CidrAddressBlock.create(InetAddresses.forString(GOOD_IP.get()), 32)))
            .build());

    String encodedCertificate = Base64.getEncoder().encodeToString(certificate.getEncoded());
    credentials =
        new TlsCredentials(
            true, Optional.empty(), Optional.of(encodedCertificate), GOOD_IP, certificateChecker);
    doSuccessfulTest("login_valid.xml");
  }

  @Test
  void testSuccess_withGoodCredentialsIpv6() throws Exception {
    persistResource(
        getRegistrarBuilder()
            .setIpAddressAllowList(
                ImmutableList.of(CidrAddressBlock.create("2001:db8:0:0:0:0:1:1/32")))
            .build());
    credentials =
        new TlsCredentials(true, GOOD_CERT_HASH, encodedCertString, GOOD_IPV6, certificateChecker);
    doSuccessfulTest("login_valid.xml");
  }

  @Test
  void testSuccess_withIpv6AddressInSubnet() throws Exception {
    persistResource(
        getRegistrarBuilder()
            .setIpAddressAllowList(
                ImmutableList.of(CidrAddressBlock.create("2001:db8:0:0:0:0:1:1/32")))
            .build());
    credentials =
        new TlsCredentials(true, GOOD_CERT_HASH, encodedCertString, GOOD_IPV6, certificateChecker);
    doSuccessfulTest("login_valid.xml");
  }

  @Test
  void testSuccess_withIpv4AddressInSubnet() throws Exception {
    persistResource(
        getRegistrarBuilder()
            .setIpAddressAllowList(ImmutableList.of(CidrAddressBlock.create("192.168.1.255/24")))
            .build());
    credentials =
        new TlsCredentials(true, GOOD_CERT_HASH, encodedCertString, GOOD_IP, certificateChecker);
    doSuccessfulTest("login_valid.xml");
  }

  @Test
  void testFailure_incorrectClientCertificateHash() throws Exception {
    persistResource(getRegistrarBuilder().build());
    String proxyEncoded = encodeX509CertificateFromPemString(BAD_CERT.get());
    credentials =
        new TlsCredentials(
            true, BAD_CERT_HASH, Optional.of(proxyEncoded), GOOD_IP, certificateChecker);
    doFailingTest("login_valid.xml", BadRegistrarCertificateException.class);
  }

  @Test
  // TODO(Sarahbot): This should fail once hash fallback is removed
  void testSuccess_missingClientCertificate() throws Exception {
    persistResource(getRegistrarBuilder().build());
    credentials =
        new TlsCredentials(true, GOOD_CERT_HASH, Optional.empty(), GOOD_IP, certificateChecker);
    doSuccessfulTest("login_valid.xml");
  }

  @Test
  void testFailure_missingClientCertificateAndHash() {
    persistResource(getRegistrarBuilder().build());
    credentials =
        new TlsCredentials(true, Optional.empty(), Optional.empty(), GOOD_IP, certificateChecker);
    doFailingTest("login_valid.xml", MissingRegistrarCertificateException.class);
  }

  @Test
  void testFailure_missingClientIpAddress() {
    persistResource(
        getRegistrarBuilder()
            .setIpAddressAllowList(
                ImmutableList.of(
                    CidrAddressBlock.create(InetAddresses.forString("192.168.1.1"), 32),
                    CidrAddressBlock.create(InetAddresses.forString("2001:db8::1"), 128)))
            .build());
    credentials =
        new TlsCredentials(true, GOOD_CERT_HASH, GOOD_CERT, Optional.empty(), certificateChecker);
    doFailingTest("login_valid.xml", BadRegistrarIpAddressException.class);
  }

  @Test
  void testFailure_incorrectClientIpv4Address() {
    persistResource(
        getRegistrarBuilder()
            .setIpAddressAllowList(
                ImmutableList.of(
                    CidrAddressBlock.create(InetAddresses.forString("192.168.1.1"), 32),
                    CidrAddressBlock.create(InetAddresses.forString("2001:db8::1"), 128)))
            .build());
    credentials = new TlsCredentials(true, GOOD_CERT_HASH, GOOD_CERT, BAD_IP, certificateChecker);
    doFailingTest("login_valid.xml", BadRegistrarIpAddressException.class);
  }

  @Test
  void testFailure_incorrectClientIpv6Address() {
    persistResource(
        getRegistrarBuilder()
            .setIpAddressAllowList(
                ImmutableList.of(
                    CidrAddressBlock.create(InetAddresses.forString("192.168.1.1"), 32),
                    CidrAddressBlock.create(InetAddresses.forString("2001:db8::1"), 128)))
            .build());
    credentials = new TlsCredentials(true, GOOD_CERT_HASH, GOOD_CERT, BAD_IPV6, certificateChecker);
    doFailingTest("login_valid.xml", BadRegistrarIpAddressException.class);
  }
}
