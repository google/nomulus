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
import static google.registry.tmch.TmchTestData.loadSmd;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.config.RegistryConfig.ConfigModule.TmchCaMode;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import google.registry.tmch.TmchXmlSignature.CertificateSignatureException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertificateRevokedException;
import javax.xml.crypto.dsig.XMLSignatureException;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junitpioneer.jupiter.cartesian.CartesianTest;
import org.junitpioneer.jupiter.cartesian.CartesianTest.Values;

/**
 * Unit tests for {@link TmchXmlSignature}.
 *
 * <p>This class does not verify if the SMD itself is revoked, which is different from if the
 * certificate signging the SMD is revoked, as it is not a crypto issue.
 */
class TmchXmlSignatureTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  // This should be a date which falls within the validity range of the test files contained in the
  // smd/ directory. Note that test files claiming to be valid for a particular date
  // range in the file header may not actually be valid the whole time, because they contain an
  // embedded (intermediate) certificate which might have a shorter validity range.
  //
  // New versions of the test files are published every few years by ICANN, and available at in the
  // "Signed Mark Data Files" section of:
  //
  // https://newgtlds.icann.org/en/about/trademark-clearinghouse/registries-registrars
  //
  // When updating this date, also update the "time travel" dates in two tests below, which test to
  // make sure that dates before and after the validity window result in rejection.
  private final FakeClock clock = new FakeClock(DateTime.parse("2023-01-15T23:15:37.4Z"));

  private byte[] smdData;
  private TmchXmlSignature tmchXmlSignature =
      new TmchXmlSignature(new TmchCertificateAuthority(TmchCaMode.PILOT, clock));

  @Test
  void testActive() throws Exception {
    smdData = loadSmd("smd/active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testRevoked() throws Exception {
    smdData = loadSmd("smd/revoked.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testInvalid() {
    smdData = loadSmd("smd/invalid.smd");
    assertThrows(XMLSignatureException.class, () -> tmchXmlSignature.verify(smdData));
  }

  @Test
  void testTmvCertRevoked() {
    smdData = loadSmd("smd/tmv-cert-revoked.smd");
    CertificateRevokedException e =
        assertThrows(CertificateRevokedException.class, () -> tmchXmlSignature.verify(smdData));
    assertThat(e).hasMessageThat().contains("Certificate has been revoked");
  }

  @Test
  void testWrongCertificateAuthority() {
    tmchXmlSignature =
        new TmchXmlSignature(new TmchCertificateAuthority(TmchCaMode.PRODUCTION, clock));
    smdData = loadSmd("smd/active.smd");
    CertificateSignatureException e =
        assertThrows(CertificateSignatureException.class, () -> tmchXmlSignature.verify(smdData));
    assertThat(e).hasMessageThat().contains("Signature does not match");
  }

  @Test
  void testTimeTravelBeforeCertificateWasCreated() {
    smdData = loadSmd("smd/active.smd");
    clock.setTo(DateTime.parse("2021-05-01T00:00:00Z"));
    assertThrows(CertificateNotYetValidException.class, () -> tmchXmlSignature.verify(smdData));
  }

  @Test
  void testTimeTravelAfterCertificateHasExpired() {
    smdData = loadSmd("smd/active.smd");
    clock.setTo(DateTime.parse("2028-06-01T00:00:00Z"));
    assertThrows(CertificateExpiredException.class, () -> tmchXmlSignature.verify(smdData));
  }

  @CartesianTest
  void testInd(
      @Values(strings = {"Agent", "Holder"}) String contact,
      @Values(strings = {"Court", "Trademark", "TreatyStatute"}) String type,
      @Values(strings = {"Arab", "Chinese", "English", "French"}) String language,
      @Values(strings = {"Active", "Revoked"}) String status)
      throws Exception {
    smdData =
        loadSmd(
            String.format(
                "idn/%s-%s/%s-%s-%s-%s.smd", contact, language, type, contact, language, status));
    tmchXmlSignature.verify(smdData);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Arab", "Chinese", "English", "French"})
  void testIndTmvRevoked(String language) {
    smdData =
        loadSmd(
            String.format("idn/RevokedCert/TMVRevoked-Trademark-Agent-%s-Active.smd", language));
    CertificateRevokedException e =
        assertThrows(CertificateRevokedException.class, () -> tmchXmlSignature.verify(smdData));
    assertThat(e).hasMessageThat().contains("Certificate has been revoked");
  }
}
