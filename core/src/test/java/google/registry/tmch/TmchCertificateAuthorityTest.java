// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tmch;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.config.RegistryConfig.ConfigModule.TmchCaMode.PILOT;
import static google.registry.config.RegistryConfig.ConfigModule.TmchCaMode.PRODUCTION;
import static google.registry.tmch.TmchTestData.loadFile;
import static google.registry.util.ResourceUtils.readResourceUtf8;
import static google.registry.util.X509Utils.loadCertificate;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.tmch.TmchCrl;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import java.security.SignatureException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertificateRevokedException;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link TmchCertificateAuthority}. */
class TmchCertificateAuthorityTest {

  // This certificate is extracted from the SMD file smd/active.smd. It is the
  // intermediate (TMV) certificate that was signed by the root (CA) cert and signed the SMD
  // file.
  private static final String GOOD_TMV_CERTIFICATE = loadFile("crypto/icann-tmv-test-good.crt");
  // This certificate is extracted from the SMD file smd/tmv-cert-revoked.smd. It is the (revoked)
  // intermediate (TMV) certificate that was signed by the root (CA) cert and signed the SMD
  // file.
  private static final String REVOKED_TMV_CERTIFICATE =
      loadFile("crypto/icann-tmv-test-revoked.crt");

  @RegisterExtension
  public final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private final FakeClock clock = new FakeClock(DateTime.parse("2022-11-20T00:00:00Z"));

  @Test
  void testFailure_prodRootExpired() {
    TmchCertificateAuthority tmchCertificateAuthority =
        new TmchCertificateAuthority(PRODUCTION, clock);
    clock.setTo(DateTime.parse("2500-01-01T00:00:00Z"));
    CertificateExpiredException e =
        assertThrows(
            CertificateExpiredException.class, tmchCertificateAuthority::getAndValidateRoot);
    assertThat(e).hasMessageThat().contains("NotAfter");
  }

  @Test
  void testFailure_prodRootNotYetValid() {
    TmchCertificateAuthority tmchCertificateAuthority =
        new TmchCertificateAuthority(PRODUCTION, clock);
    clock.setTo(DateTime.parse("2000-01-01T00:00:00Z"));
    CertificateNotYetValidException e =
        assertThrows(
            CertificateNotYetValidException.class, tmchCertificateAuthority::getAndValidateRoot);
    assertThat(e).hasMessageThat().contains("NotBefore");
  }

  @Test
  void testFailure_crlDoesntMatchCerts() {
    // Use the prod cl, which won't match our test certificate.
    TmchCertificateAuthority tmchCertificateAuthority = new TmchCertificateAuthority(PILOT, clock);
    TmchCrl.set(
        readResourceUtf8(TmchCertificateAuthority.class, "icann-tmch.crl"), "http://cert.crl");
    SignatureException e =
        assertThrows(
            SignatureException.class,
            () -> tmchCertificateAuthority.verify(loadCertificate(GOOD_TMV_CERTIFICATE)));
    assertThat(e).hasMessageThat().contains("Signature does not match");
  }

  @Test
  void testSuccess_verify() throws Exception {
    TmchCertificateAuthority tmchCertificateAuthority = new TmchCertificateAuthority(PILOT, clock);
    tmchCertificateAuthority.verify(loadCertificate(GOOD_TMV_CERTIFICATE));
  }

  @Test
  void testFailure_verifySignatureDoesntMatch() {
    TmchCertificateAuthority tmchCertificateAuthority =
        new TmchCertificateAuthority(PRODUCTION, clock);
    SignatureException e =
        assertThrows(
            SignatureException.class,
            () -> tmchCertificateAuthority.verify(loadCertificate(GOOD_TMV_CERTIFICATE)));
    assertThat(e).hasMessageThat().contains("Signature does not match");
  }

  @Test
  void testFailure_verifyRevoked() {
    TmchCertificateAuthority tmchCertificateAuthority = new TmchCertificateAuthority(PILOT, clock);
    CertificateRevokedException thrown =
        assertThrows(
            CertificateRevokedException.class,
            () -> tmchCertificateAuthority.verify(loadCertificate(REVOKED_TMV_CERTIFICATE)));
    assertThat(thrown).hasMessageThat().contains("revoked, reason: UNSPECIFIED");
  }
}
