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

package google.registry.util;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.util.CertificateChecker.CertificateViolation.ALGORITHM_CONSTRAINED;
import static google.registry.util.CertificateChecker.CertificateViolation.EXPIRED;
import static google.registry.util.CertificateChecker.CertificateViolation.NOT_YET_VALID;
import static google.registry.util.CertificateChecker.CertificateViolation.RSA_KEY_LENGTH_TOO_SHORT;
import static google.registry.util.CertificateChecker.CertificateViolation.VALIDITY_LENGTH_TOO_LONG;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableSortedMap;
import google.registry.testing.FakeClock;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
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
}
