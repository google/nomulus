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
import static org.junit.jupiter.api.Assertions.assertThrows;

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
  private static final String GOOD_CERTIFICATE =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDyzCCArOgAwIBAgIUJnhiVrxAxgwkLJzHPm1w/lBoNs4wDQYJKoZIhvcNAQEL\n"
          + "BQAwdTELMAkGA1UEBhMCVVMxETAPBgNVBAgMCE5ldyBZb3JrMREwDwYDVQQHDAhO\n"
          + "ZXcgWW9yazEPMA0GA1UECgwGR29vZ2xlMR0wGwYDVQQLDBRkb21haW4tcmVnaXN0\n"
          + "cnktdGVzdDEQMA4GA1UEAwwHY2xpZW50MTAeFw0yMDEwMTIxNzU5NDFaFw0yMTA0\n"
          + "MzAxNzU5NDFaMHUxCzAJBgNVBAYTAlVTMREwDwYDVQQIDAhOZXcgWW9yazERMA8G\n"
          + "A1UEBwwITmV3IFlvcmsxDzANBgNVBAoMBkdvb2dsZTEdMBsGA1UECwwUZG9tYWlu\n"
          + "LXJlZ2lzdHJ5LXRlc3QxEDAOBgNVBAMMB2NsaWVudDEwggEiMA0GCSqGSIb3DQEB\n"
          + "AQUAA4IBDwAwggEKAoIBAQC0msirO7kXyGEC93stsNYGc02Z77Q2qfHFwaGYkUG8\n"
          + "QvOF5SWN+jwTo5Td6Jj26A26a8MLCtK45TCBuMRNcUsHhajhT19ocphO20iY3zhi\n"
          + "ycwV1id0iwME4kPd1m57BELRE9tUPOxF81/JQXdR1fwT5KRVHYRDWZhaZ5aBmlZY\n"
          + "3t/H9Ly0RBYyApkMaGs3nlb94OOug6SouUfRt02S59ja3wsE2SVF/Eui647OXP7O\n"
          + "QdYXofxuqLoNkE8EnAdl43/enGLiCIVd0G2lABibFF+gbxTtfgbg7YtfUZJdL+Mb\n"
          + "RAcAtuLXEamNQ9H63JgVF16PlQVCDz2XyI3uCfPpDDiBAgMBAAGjUzBRMB0GA1Ud\n"
          + "DgQWBBQ26bWk8qfEBjXs/xZ4m8JZyalnITAfBgNVHSMEGDAWgBQ26bWk8qfEBjXs\n"
          + "/xZ4m8JZyalnITAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQAZ\n"
          + "VcsgslBKanKOieJ5ik2d9qzOMXKfBuWPRFWbkC3t9i5awhHqnGAaj6nICnnMZIyt\n"
          + "rdx5lZW5aaQyf0EP/90JAA8Xmty4A6MXmEjQAMiCOpP3A7eeS6Xglgi8IOZl4/bg\n"
          + "LonW62TUkilo5IiFt/QklFTeHIjXB+OvA8+2Quqyd+zp7v6KnhXjvaomim78DhwE\n"
          + "0PIUnjmiRpGpHfTVioTdfhPHZ2Y93Y8K7juL93sQog9aBu5m9XRJCY6wGyWPE83i\n"
          + "kmLfGzjcnaJ6kqCd9xQRFZ0JwHmGlkAQvFoeengbNUqSyjyVgsOoNkEsrWwe/JFO\n"
          + "iqBvjEhJlvRoefvkdR98\n"
          + "-----END CERTIFICATE-----\n";
  private static final String BAD_CERTIFICATE =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDvTCCAqWgAwIBAgIJANoEy6mYwalPMA0GCSqGSIb3DQEBCwUAMHUxCzAJBgNV\n"
          + "BAYTAlVTMREwDwYDVQQIDAhOZXcgWW9yazERMA8GA1UEBwwITmV3IFlvcmsxDzAN\n"
          + "BgNVBAoMBkdvb2dsZTEdMBsGA1UECwwUZG9tYWluLXJlZ2lzdHJ5LXRlc3QxEDAO\n"
          + "BgNVBAMMB2NsaWVudDIwHhcNMTUwODI2MTkyODU3WhcNNDMwMTExMTkyODU3WjB1\n"
          + "MQswCQYDVQQGEwJVUzERMA8GA1UECAwITmV3IFlvcmsxETAPBgNVBAcMCE5ldyBZ\n"
          + "b3JrMQ8wDQYDVQQKDAZHb29nbGUxHTAbBgNVBAsMFGRvbWFpbi1yZWdpc3RyeS10\n"
          + "ZXN0MRAwDgYDVQQDDAdjbGllbnQyMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIB\n"
          + "CgKCAQEAw2FtuDyoR+rUJHp6k7KwaoHGHPV1xnC8IpG9O0SZubOXrFrnBHggBsbu\n"
          + "+DsknbHXjmoihSFFem0KQqJg5y34aDAHXQV3iqa7nDfb1x4oc5voVz9gqjdmGKNm\n"
          + "WF4MTIPNMu8KY52M852mMCxODK+6MZYp7wCmVa63KdCm0bW/XsLgoA/+FVGwKLhf\n"
          + "UqFzt10Cf+87zl4VHrSaJqcHBYM6yAO5lvkr5VC6g8rRQ+dJ+pBT2D99YpSF1aFc\n"
          + "rWbBreIypixZAnXm/Xoogu6RnohS29VCJp2dXFAJmKXGwyKNQFXfEKxZBaBi8uKH\n"
          + "XF459795eyF9xHgSckEgu7jZlxOk6wIDAQABo1AwTjAdBgNVHQ4EFgQUv26AsQyc\n"
          + "kLOjkhqcFLOuueB33l4wHwYDVR0jBBgwFoAUv26AsQyckLOjkhqcFLOuueB33l4w\n"
          + "DAYDVR0TBAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEANBuV+QDISSnGAEHKbR40\n"
          + "zUYdOjdZ399zcFNqTSPHwmE0Qu8pbmXhofpBfjzrcv0tkVbhSLYnT22qhx7aDmhb\n"
          + "bOS8CeVYCwl5eiDTkJly3pRZLzJpy+UT5z8SPxO3MrTqn+wuj0lBpWRTBCWYAUpr\n"
          + "IFRmgVB3IwVb60UIuxhmuk8TVss2SzNrdhdt36eAIPJ0RWEb0KHYHi35Y6lt4f+t\n"
          + "iVk+ZR0cCbHUs7Q1RqREXHd/ICuMRLY/MsadVQ9WDqVOridh198X/OIqdx/p9kvJ\n"
          + "1R80jDcVGNhYVXLmHu4ho4xrOaliSYvUJSCmaaSEGVZ/xE5PI7S6A8RMdj0iXLSt\n"
          + "Bg==\n"
          + "-----END CERTIFICATE-----\n";

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
  void test_checkCertificate_validCertificateString() throws Exception {
    fakeClock.setTo(DateTime.parse("2020-11-01T00:00:00Z"));
    assertThat(certificateChecker.checkCertificate(GOOD_CERTIFICATE)).isEmpty();
    assertThat(certificateChecker.checkCertificate(BAD_CERTIFICATE))
        .containsExactly(VALIDITY_LENGTH_TOO_LONG);
  }

  @Test
  void test_checkCertificate_invalidCertificateString() throws Exception {
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
}
