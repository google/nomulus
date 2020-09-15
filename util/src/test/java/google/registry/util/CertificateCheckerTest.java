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
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableSet;
import google.registry.util.CertificateChecker.CertificateViolation;
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
public class CertificateCheckerTest {

  private static final String SSL_HOST = "www.example.tld";

  private static CertificateChecker certificateChecker = new CertificateChecker(825, 30, 2048);

  @Test
  void test_compliantCertificate() throws Exception {
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                SSL_HOST,
                DateTime.now(UTC).minusDays(5).toDate(),
                DateTime.now(UTC).plusDays(80).toDate())
            .cert();
    assertThat(certificateChecker.checkCertificate(certificate, DateTime.now(UTC).toDate()))
        .isEqualTo(ImmutableSet.of());
  }

  @Test
  void test_certificateWithSeveralIssues() throws Exception {
    KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
    AlgorithmParameters apParam = AlgorithmParameters.getInstance("EC");
    apParam.init(new ECGenParameterSpec("secp128r1"));
    ECParameterSpec spec = apParam.getParameterSpec(ECParameterSpec.class);
    keyGen.initialize(spec, new SecureRandom());

    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                keyGen.generateKeyPair(),
                SSL_HOST,
                DateTime.now(UTC).plusDays(5).toDate(),
                DateTime.now(UTC).plusDays(1000).toDate())
            .cert();

    assertThat(certificateChecker.checkCertificate(certificate, DateTime.now(UTC).toDate()))
        .isEqualTo(
            ImmutableSet.of(
                CertificateViolation.NOT_YET_VALID,
                CertificateViolation.EC_CURVE,
                CertificateViolation.VALIDITY_LENGTH));
  }

  @Test
  void test_expiredCertificate() throws Exception {
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                SSL_HOST,
                DateTime.now(UTC).minusDays(50).toDate(),
                DateTime.now(UTC).minusDays(10).toDate())
            .cert();
    assertThat(certificateChecker.checkCertificate(certificate, DateTime.now(UTC).toDate()))
        .isEqualTo(ImmutableSet.of(CertificateViolation.EXPIRED));
  }

  @Test
  void test_notYetValid() throws Exception {
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                SSL_HOST,
                DateTime.now(UTC).plusDays(10).toDate(),
                DateTime.now(UTC).plusDays(50).toDate())
            .cert();
    assertThat(certificateChecker.checkCertificate(certificate, DateTime.now(UTC).toDate()))
        .isEqualTo(ImmutableSet.of(CertificateViolation.NOT_YET_VALID));
  }

  @Test
  void test_checkValidityLength() throws Exception {
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                SSL_HOST,
                DateTime.now(UTC).minusDays(10).toDate(),
                DateTime.now(UTC).plusDays(1000).toDate())
            .cert();
    assertThat(certificateChecker.checkCertificate(certificate, DateTime.now(UTC).toDate()))
        .isEqualTo(ImmutableSet.of(CertificateViolation.VALIDITY_LENGTH));
  }

  @Test
  void test_nearingExpiration() throws Exception {
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                SSL_HOST,
                DateTime.now(UTC).minusDays(50).toDate(),
                DateTime.now(UTC).plusDays(10).toDate())
            .cert();
    assertThat(certificateChecker.checkCertificate(certificate, DateTime.now(UTC).toDate()))
        .isEqualTo(ImmutableSet.of(CertificateViolation.NEARING_EXPIRATION));
  }

  @Test
  void test_checkRsaKeyLength() throws Exception {
    // Key length too low
    KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", new BouncyCastleProvider());
    keyGen.initialize(1024, new SecureRandom());

    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                keyGen.generateKeyPair(),
                SSL_HOST,
                DateTime.now(UTC).minusDays(5).toDate(),
                DateTime.now(UTC).plusDays(100).toDate())
            .cert();

    assertThat(certificateChecker.checkCertificate(certificate, DateTime.now(UTC).toDate()))
        .isEqualTo(ImmutableSet.of(CertificateViolation.RSA_KEY_LENGTH));

    // Key length higher than required
    keyGen = KeyPairGenerator.getInstance("RSA", new BouncyCastleProvider());
    keyGen.initialize(4096, new SecureRandom());

    certificate =
        SelfSignedCaCertificate.create(
                keyGen.generateKeyPair(),
                SSL_HOST,
                DateTime.now(UTC).minusDays(5).toDate(),
                DateTime.now(UTC).plusDays(100).toDate())
            .cert();

    assertThat(certificateChecker.checkCertificate(certificate, DateTime.now(UTC).toDate()))
        .isEqualTo(ImmutableSet.of());
  }

  @Test
  void test_checkEcKeyLength() throws Exception {
    // Key lower than P-256
    KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
    AlgorithmParameters apParam = AlgorithmParameters.getInstance("EC");
    apParam.init(new ECGenParameterSpec("secp128r1"));
    ECParameterSpec spec = apParam.getParameterSpec(ECParameterSpec.class);
    keyGen.initialize(spec, new SecureRandom());

    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                keyGen.generateKeyPair(),
                SSL_HOST,
                DateTime.now(UTC).minusDays(5).toDate(),
                DateTime.now(UTC).plusDays(100).toDate())
            .cert();

    assertThat(certificateChecker.checkCertificate(certificate, DateTime.now(UTC).toDate()))
        .isEqualTo(ImmutableSet.of(CertificateViolation.EC_CURVE));

    // Curve higher than P-256
    keyGen = KeyPairGenerator.getInstance("EC");
    apParam = AlgorithmParameters.getInstance("EC");
    apParam.init(new ECGenParameterSpec("secp521r1"));
    spec = apParam.getParameterSpec(ECParameterSpec.class);
    keyGen.initialize(spec, new SecureRandom());

    certificate =
        SelfSignedCaCertificate.create(
                keyGen.generateKeyPair(),
                SSL_HOST,
                DateTime.now(UTC).minusDays(5).toDate(),
                DateTime.now(UTC).plusDays(100).toDate())
            .cert();

    assertThat(certificateChecker.checkCertificate(certificate, DateTime.now(UTC).toDate()))
        .isEqualTo(ImmutableSet.of(CertificateViolation.EC_CURVE));

    // Curve is P-256
    keyGen = KeyPairGenerator.getInstance("EC");
    apParam = AlgorithmParameters.getInstance("EC");
    apParam.init(new ECGenParameterSpec("secp256k1"));
    spec = apParam.getParameterSpec(ECParameterSpec.class);
    keyGen.initialize(spec, new SecureRandom());

    certificate =
        SelfSignedCaCertificate.create(
                keyGen.generateKeyPair(),
                SSL_HOST,
                DateTime.now(UTC).minusDays(5).toDate(),
                DateTime.now(UTC).plusDays(100).toDate())
            .cert();

    assertThat(certificateChecker.checkCertificate(certificate, DateTime.now(UTC).toDate()))
        .isEqualTo(ImmutableSet.of());
  }
}
