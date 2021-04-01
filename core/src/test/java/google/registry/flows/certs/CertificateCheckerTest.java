// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.flows.certs;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.flows.certs.CertificateChecker.CertificateViolation.ALGORITHM_CONSTRAINED;
import static google.registry.flows.certs.CertificateChecker.CertificateViolation.EXPIRED;
import static google.registry.flows.certs.CertificateChecker.CertificateViolation.INVALID_ECDSA_CURVE;
import static google.registry.flows.certs.CertificateChecker.CertificateViolation.NOT_YET_VALID;
import static google.registry.flows.certs.CertificateChecker.CertificateViolation.RSA_KEY_LENGTH_TOO_SHORT;
import static google.registry.flows.certs.CertificateChecker.CertificateViolation.VALIDITY_LENGTH_TOO_LONG;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT3;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.testing.FakeClock;
import google.registry.util.SelfSignedCaCertificate;
import java.security.AlgorithmParameters;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CertificateChecker} */
class CertificateCheckerTest {

  private static final String SSL_HOST = "www.example.tld";

  private FakeClock fakeClock = new FakeClock();
  private CertificateChecker certificateChecker =
      new CertificateChecker(
          ImmutableSortedMap.of(START_OF_TIME, 825, DateTime.parse("2020-09-01T00:00:00Z"), 398),
          30,
          2048,
          ImmutableSet.of("secp256r1", "secp384r1"),
          fakeClock);

  @Test
  void test_checkCertificate_compliantCertPasses() throws Exception {
    fakeClock.setTo(DateTime.parse("2020-10-01T00:00:00Z"));
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                SSL_HOST,
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-10-01T00:00:00Z"))
            .cert();
    assertThat(certificateChecker.checkCertificate(certificate)).isEmpty();
  }

  @Test
  void test_checkCertificate_severalViolations() throws Exception {
    fakeClock.setTo(DateTime.parse("2010-01-01T00:00:00Z"));
    KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", new BouncyCastleProvider());
    keyGen.initialize(1024, new SecureRandom());

    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                keyGen.generateKeyPair(),
                SSL_HOST,
                DateTime.parse("2010-04-01T00:00:00Z"),
                DateTime.parse("2014-07-01T00:00:00Z"))
            .cert();

    assertThat(certificateChecker.checkCertificate(certificate))
        .containsExactly(NOT_YET_VALID, VALIDITY_LENGTH_TOO_LONG, RSA_KEY_LENGTH_TOO_SHORT);
  }

  @Test
  void test_checkCertificate_expiredCertificate() throws Exception {
    fakeClock.setTo(DateTime.parse("2014-01-01T00:00:00Z"));
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                SSL_HOST,
                DateTime.parse("2010-04-01T00:00:00Z"),
                DateTime.parse("2012-07-01T00:00:00Z"))
            .cert();
    assertThat(certificateChecker.checkCertificate(certificate)).containsExactly(EXPIRED);
  }

  @Test
  void test_checkCertificate_notYetValid() throws Exception {
    fakeClock.setTo(DateTime.parse("2010-01-01T00:00:00Z"));
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                SSL_HOST,
                DateTime.parse("2010-04-01T00:00:00Z"),
                DateTime.parse("2012-07-01T00:00:00Z"))
            .cert();
    assertThat(certificateChecker.checkCertificate(certificate)).containsExactly(NOT_YET_VALID);
  }

  @Test
  void test_checkCertificate_validityLengthWayTooLong() throws Exception {
    fakeClock.setTo(DateTime.parse("2020-10-01T00:00:00Z"));
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                SSL_HOST,
                DateTime.parse("2016-04-01T00:00:00Z"),
                DateTime.parse("2021-07-01T00:00:00Z"))
            .cert();
    assertThat(certificateChecker.checkCertificate(certificate))
        .containsExactly(VALIDITY_LENGTH_TOO_LONG);
  }

  @Test
  void test_checkCertificate_olderValidityLengthIssuedAfterCutoff() throws Exception {
    fakeClock.setTo(DateTime.parse("2020-10-01T00:00:00Z"));
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                SSL_HOST,
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2022-10-01T00:00:00Z"))
            .cert();
    assertThat(certificateChecker.checkCertificate(certificate))
        .containsExactly(VALIDITY_LENGTH_TOO_LONG);
  }

  @Test
  void test_checkCertificate_olderValidityLengthIssuedBeforeCutoff() throws Exception {
    fakeClock.setTo(DateTime.parse("2020-10-01T00:00:00Z"));
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                SSL_HOST,
                DateTime.parse("2019-09-01T00:00:00Z"),
                DateTime.parse("2021-10-01T00:00:00Z"))
            .cert();
    assertThat(certificateChecker.checkCertificate(certificate)).isEmpty();
  }

  @Test
  void test_checkCertificate_rsaKeyLengthTooShort() throws Exception {
    fakeClock.setTo(DateTime.parse("2020-10-01T00:00:00Z"));
    KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", new BouncyCastleProvider());
    keyGen.initialize(1024, new SecureRandom());

    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                keyGen.generateKeyPair(),
                SSL_HOST,
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-10-01T00:00:00Z"))
            .cert();

    assertThat(certificateChecker.checkCertificate(certificate))
        .containsExactly(RSA_KEY_LENGTH_TOO_SHORT);
  }

  @Test
  void test_checkCertificate_rsaKeyLengthLongerThanRequired() throws Exception {
    fakeClock.setTo(DateTime.parse("2020-10-01T00:00:00Z"));
    KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", new BouncyCastleProvider());
    keyGen.initialize(4096, new SecureRandom());

    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                keyGen.generateKeyPair(),
                SSL_HOST,
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-10-01T00:00:00Z"))
            .cert();

    assertThat(certificateChecker.checkCertificate(certificate)).isEmpty();
  }

  @Test
  void test_checkCertificate_invalidAlgorithm() throws Exception {
    fakeClock.setTo(DateTime.parse("2020-10-01T00:00:00Z"));
    KeyPairGenerator keyGen = KeyPairGenerator.getInstance("DSA", new BouncyCastleProvider());
    keyGen.initialize(2048, new SecureRandom());

    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                keyGen.generateKeyPair(),
                SSL_HOST,
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-10-01T00:00:00Z"))
            .cert();

    assertThat(certificateChecker.checkCertificate(certificate))
        .containsExactly(ALGORITHM_CONSTRAINED);
  }

  @Test
  void test_checkCertificate_validCertificateString() {
    fakeClock.setTo(DateTime.parse("2020-11-01T00:00:00Z"));
    assertThat(certificateChecker.checkCertificate(SAMPLE_CERT3)).isEmpty();
    assertThat(certificateChecker.checkCertificate(SAMPLE_CERT))
        .containsExactly(VALIDITY_LENGTH_TOO_LONG);
  }

  @Test
  void test_checkCertificate_invalidCertificateString() {
    fakeClock.setTo(DateTime.parse("2020-11-01T00:00:00Z"));
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> certificateChecker.checkCertificate("bad certificate string"));
    assertThat(thrown).hasMessageThat().isEqualTo("Unable to read given certificate.");
  }

  @Test
  void test_isNearingExpiration_yesItIs() throws Exception {
    fakeClock.setTo(DateTime.parse("2021-09-20T00:00:00Z"));
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                SSL_HOST,
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-10-01T00:00:00Z"))
            .cert();
    assertThat(certificateChecker.isNearingExpiration(certificate)).isTrue();
  }

  @Test
  void test_isNearingExpiration_noItsNot() throws Exception {
    fakeClock.setTo(DateTime.parse("2021-07-20T00:00:00Z"));
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                SSL_HOST,
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-10-01T00:00:00Z"))
            .cert();
    assertThat(certificateChecker.isNearingExpiration(certificate)).isFalse();
  }

  @Test
  void test_CertificateViolation_RsaKeyLengthDisplayMessageFormatsCorrectly() {
    assertThat(RSA_KEY_LENGTH_TOO_SHORT.getDisplayMessage(certificateChecker))
        .isEqualTo("RSA key length is too short; the minimum allowed length is 2048 bits.");
  }

  @Test
  void test_CertificateViolation_validityLengthDisplayMessageFormatsCorrectly() {
    assertThat(VALIDITY_LENGTH_TOO_LONG.getDisplayMessage(certificateChecker))
        .isEqualTo(
            "Certificate validity period is too long; it must be less than or equal to 398 days.");
  }

  @Test
  void test_checkCurveName_invalidCurve_returnsViolation() throws Exception {
    fakeClock.setTo(DateTime.parse("2020-10-01T00:00:00Z"));
    // Invalid curve
    KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
    AlgorithmParameters apParam = AlgorithmParameters.getInstance("EC");
    apParam.init(new ECGenParameterSpec("secp128r1"));
    ECParameterSpec spec = apParam.getParameterSpec(ECParameterSpec.class);
    keyGen.initialize(spec, new SecureRandom());
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                keyGen.generateKeyPair(),
                SSL_HOST,
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-10-01T00:00:00Z"))
            .cert();
    assertThat(certificateChecker.checkCertificate(certificate))
        .containsExactly(INVALID_ECDSA_CURVE);
  }

  @Test
  void test_checkCurveName_p256Curve_returnsNoViolations() throws Exception {
    fakeClock.setTo(DateTime.parse("2020-10-01T00:00:00Z"));
    // valid P-256 curve
    KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
    AlgorithmParameters apParam = AlgorithmParameters.getInstance("EC");
    apParam.init(new ECGenParameterSpec("secp256r1"));
    ECParameterSpec spec = apParam.getParameterSpec(ECParameterSpec.class);
    keyGen.initialize(spec, new SecureRandom());
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                keyGen.generateKeyPair(),
                SSL_HOST,
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-10-01T00:00:00Z"))
            .cert();
    assertThat(certificateChecker.checkCertificate(certificate)).isEmpty();
  }

  @Test
  void test_checkCurveName_p384Curve_returnsNoViolations() throws Exception {
    fakeClock.setTo(DateTime.parse("2020-10-01T00:00:00Z"));
    // valid P-384 curve
    KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
    AlgorithmParameters apParam = AlgorithmParameters.getInstance("EC");
    apParam.init(new ECGenParameterSpec("secp384r1"));
    ECParameterSpec spec = apParam.getParameterSpec(ECParameterSpec.class);
    keyGen.initialize(spec, new SecureRandom());
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                keyGen.generateKeyPair(),
                SSL_HOST,
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-10-01T00:00:00Z"))
            .cert();
    assertThat(certificateChecker.checkCertificate(certificate)).isEmpty();
  }
}
