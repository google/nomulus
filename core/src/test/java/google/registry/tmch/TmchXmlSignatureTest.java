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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Unit tests for {@link TmchXmlSignature}.
 *
 * <p>This class does not test the {@code revoked/smd/} folder because it's not a crypto issue.
 */
class TmchXmlSignatureTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  // This should be a date which falls within the validity range of the test files contained in the
  // active directory. Note that test files claiming to be valid for a particular date
  // range in the file header may not actually be valid the whole time, because they contain an
  // embedded certificate which might have a shorter validity range.
  //
  // New versions of the test files are published every few years by ICANN, and available at in the
  // Signed Mark Data Files section of:
  //
  // https://newgtlds.icann.org/en/about/trademark-clearinghouse/registries-registrars
  //
  // The link labeled "IDN-SMD Testing Files" leads to a .tar.gz file containing the test files
  // which in our directory structure reside in active, revoked/smd and revoked/tmv. The link
  // labeled "Invalid SMD" leads to a single file in invalid. The test files are signed by the
  // pilot root certificate, so they need to be updated together with the cert when the cert
  // rotates, which typically happens at the same time production cert rotates.
  //
  // When updating this date, also update the "time travel" dates below, which test to make sure
  // that dates before and after the validity window result in rejection.
  private final FakeClock clock = new FakeClock(DateTime.parse("2023-01-15T23:15:37.4Z"));

  private byte[] smdData;
  private TmchXmlSignature tmchXmlSignature =
      new TmchXmlSignature(new TmchCertificateAuthority(TmchCaMode.PILOT, clock));

  @BeforeEach
  void beforeEach() {}

  @Test
  void testWrongCertificateAuthority() {
    tmchXmlSignature =
        new TmchXmlSignature(new TmchCertificateAuthority(TmchCaMode.PRODUCTION, clock));
    smdData = loadSmd("active/Court-Agent-Arab-Active.smd");
    CertificateSignatureException e =
        assertThrows(CertificateSignatureException.class, () -> tmchXmlSignature.verify(smdData));
    assertThat(e).hasMessageThat().contains("Signature does not match");
  }

  @Test
  void testTimeTravelBeforeCertificateWasCreated() {
    smdData = loadSmd("active/Court-Agent-Arab-Active.smd");
    clock.setTo(DateTime.parse("2021-05-01T00:00:00Z"));
    assertThrows(CertificateNotYetValidException.class, () -> tmchXmlSignature.verify(smdData));
  }

  @Test
  void testTimeTravelAfterCertificateHasExpired() {
    smdData = loadSmd("active/Court-Agent-Arab-Active.smd");
    clock.setTo(DateTime.parse("2028-06-01T00:00:00Z"));
    assertThrows(CertificateExpiredException.class, () -> tmchXmlSignature.verify(smdData));
  }

  @Test
  void testActiveCourtAgentArabActive() throws Exception {
    smdData = loadSmd("active/Court-Agent-Arab-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveCourtAgentChineseActive() throws Exception {
    smdData = loadSmd("active/Court-Agent-Chinese-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveCourtAgentEnglishActive() throws Exception {
    smdData = loadSmd("active/Court-Agent-English-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveCourtAgentFrenchActive() throws Exception {
    smdData = loadSmd("active/Court-Agent-French-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveCourtAgentRussianActive() throws Exception {
    smdData = loadSmd("active/Court-Agent-Russian-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveCourtHolderArabActive() throws Exception {
    smdData = loadSmd("active/Court-Holder-Arab-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveCourtHolderChineseActive() throws Exception {
    smdData = loadSmd("active/Court-Holder-Chinese-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveCourtHolderEnglishActive() throws Exception {
    smdData = loadSmd("active/Court-Holder-English-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveCourtHolderFrenchActive() throws Exception {
    smdData = loadSmd("active/Court-Holder-French-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveCourtHolderRussianActive() throws Exception {
    smdData = loadSmd("active/Court-Holder-Russian-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveTrademarkAgentArabActive() throws Exception {
    smdData = loadSmd("active/Trademark-Agent-Arab-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveTrademarkAgentChineseActive() throws Exception {
    smdData = loadSmd("active/Trademark-Agent-Chinese-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveTrademarkAgentEnglishActive() throws Exception {
    smdData = loadSmd("active/Trademark-Agent-English-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveTrademarkAgentFrenchActive() throws Exception {
    smdData = loadSmd("active/Trademark-Agent-French-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveTrademarkAgentRussianActive() throws Exception {
    smdData = loadSmd("active/Trademark-Agent-Russian-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveTrademarkHolderArabActive() throws Exception {
    smdData = loadSmd("active/Trademark-Holder-Arab-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveTrademarkHolderChineseActive() throws Exception {
    smdData = loadSmd("active/Trademark-Holder-Chinese-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveTrademarkHolderEnglishActive() throws Exception {
    smdData = loadSmd("active/Trademark-Holder-English-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveTrademarkHolderFrenchActive() throws Exception {
    smdData = loadSmd("active/Trademark-Holder-French-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveTrademarkHolderRussianActive() throws Exception {
    smdData = loadSmd("active/Trademark-Holder-Russian-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveTreatyStatuteAgentArabActive() throws Exception {
    smdData = loadSmd("active/TreatyStatute-Agent-Arab-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveTreatyStatuteAgentChineseActive() throws Exception {
    smdData = loadSmd("active/TreatyStatute-Agent-Chinese-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveTreatyStatuteAgentEnglishActive() throws Exception {
    smdData = loadSmd("active/TreatyStatute-Agent-English-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveTreatyStatuteAgentFrenchActive() throws Exception {
    smdData = loadSmd("active/TreatyStatute-Agent-French-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveTreatyStatuteAgentRussianActive() throws Exception {
    smdData = loadSmd("active/TreatyStatute-Agent-Russian-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveTreatyStatuteHolderArabActive() throws Exception {
    smdData = loadSmd("active/TreatyStatute-Holder-Arab-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveTreatyStatuteHolderChineseActive() throws Exception {
    smdData = loadSmd("active/TreatyStatute-Holder-Chinese-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveTreatyStatuteHolderEnglishActive() throws Exception {
    smdData = loadSmd("active/TreatyStatute-Holder-English-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveTreatyStatuteHolderFrenchActive() throws Exception {
    smdData = loadSmd("active/TreatyStatute-Holder-French-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testActiveTreatyStatuteHolderRussianActive() throws Exception {
    smdData = loadSmd("active/TreatyStatute-Holder-Russian-Active.smd");
    tmchXmlSignature.verify(smdData);
  }

  @Test
  void testInvalid() {
    smdData = loadSmd("invalid/smd-invalid-22nov22-en.smd");
    assertThrows(XMLSignatureException.class, () -> tmchXmlSignature.verify(smdData));
  }

  @Test
  void testRevokedTmvTmvRevokedTrademarkAgentEnglishActive() {
    smdData = loadSmd("revoked/tmv/TMVRevoked-Trademark-Agent-English-Active.smd");
    CertificateRevokedException e =
        assertThrows(CertificateRevokedException.class, () -> tmchXmlSignature.verify(smdData));
    assertThat(e).hasMessageThat().contains("Certificate has been revoked");
  }

  @Test
  void testRevokedTmvTmvRevokedTrademarkAgentRussianActive() {
    smdData = loadSmd("revoked/tmv/TMVRevoked-Trademark-Agent-Russian-Active.smd");
    CertificateRevokedException e =
        assertThrows(CertificateRevokedException.class, () -> tmchXmlSignature.verify(smdData));
    assertThat(e).hasMessageThat().contains("Certificate has been revoked");
  }
}
