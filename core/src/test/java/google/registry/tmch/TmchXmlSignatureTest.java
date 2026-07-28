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
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.config.RegistryConfig.ConfigModule.TmchCaMode;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import google.registry.tmch.TmchXmlSignature.CertificateSignatureException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertificateRevokedException;
import java.time.Instant;
import javax.xml.crypto.dsig.XMLSignatureException;
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
  private final FakeClock clock = new FakeClock(Instant.parse("2023-01-15T23:15:37.4Z"));

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
    clock.setTo(Instant.parse("2021-05-01T00:00:00Z"));
    assertThrows(CertificateNotYetValidException.class, () -> tmchXmlSignature.verify(smdData));
  }

  @Test
  void testTimeTravelAfterCertificateHasExpired() {
    smdData = loadSmd("smd/active.smd");
    clock.setTo(Instant.parse("2028-06-01T00:00:00Z"));
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

  // These tests check the structure of the decoded XML (unrelated to the decoding itself)
  @Test
  void testVerify_rootElementNotSignedMark_fails() {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <container>
           <smd:signedMark xmlns:smd="urn:ietf:params:xml:ns:signedMark-1.0" id="id-1"/>
        </container>
        """;
    XMLSignatureException e =
        assertThrows(
            XMLSignatureException.class, () -> tmchXmlSignature.verify(xml.getBytes(UTF_8)));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "Root element must be signedMark in namespace urn:ietf:params:xml:ns:signedMark-1.0");
  }

  @Test
  void testVerify_xswWrapping_fails() {
    // By default, the verifier follows the reference from a valid signature wherever it goes. This
    // could be a second signedMark object hidden elsewhere in the XML (say, inside a ds:Object,
    // which can contain anything). The SignedMark parser, however, uses the root node. We need to
    // make sure that the valid signature points to the root node, and not anything else.
    String xswXml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <smd:signedMark xmlns:smd="urn:ietf:params:xml:ns:signedMark-1.0" id="fake-id">
           <smd:id>fake-id</smd:id>
           <ds:Signature xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
              <ds:SignedInfo>
                 <ds:CanonicalizationMethod Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"/>
                 <ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"/>
                 <ds:Reference URI="#real-id">
                    <ds:Transforms>
                       <ds:Transform Algorithm=\
                       "http://www.w3.org/2000/09/xmldsig#enveloped-signature"/>
                    </ds:Transforms>
                    <ds:DigestMethod Algorithm="http://www.w3.org/2001/04/xmlenc#sha256"/>
                    <ds:DigestValue>dGVzdA==</ds:DigestValue>
                 </ds:Reference>
              </ds:SignedInfo>
              <ds:SignatureValue>dGVzdA==</ds:SignatureValue>
              <ds:Object>
                 <smd:signedMark id="real-id">
                    <smd:id>real-id</smd:id>
                 </smd:signedMark>
              </ds:Object>
           </ds:Signature>
        </smd:signedMark>
        """;

    XMLSignatureException e =
        assertThrows(
            XMLSignatureException.class, () -> tmchXmlSignature.verify(xswXml.getBytes(UTF_8)));
    assertThat(e)
        .hasMessageThat()
        .contains("Expected exactly one <smd:signedMark> element in the document");
  }

  @Test
  void testVerify_signatureDoesNotSignRoot_fails() {
    // The internal signature reference URI must match the root signed mark ID
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <smd:signedMark xmlns:smd="urn:ietf:params:xml:ns:signedMark-1.0" id="modified-id">
           <smd:id>modified-id</smd:id>
           <ds:Signature xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
              <ds:SignedInfo>
                 <ds:CanonicalizationMethod Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"/>
                 <ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"/>
                 <ds:Reference URI="#original-id">
                    <ds:Transforms>
                       <ds:Transform Algorithm=\
                       "http://www.w3.org/2000/09/xmldsig#enveloped-signature"/>
                    </ds:Transforms>
                    <ds:DigestMethod Algorithm="http://www.w3.org/2001/04/xmlenc#sha256"/>
                    <ds:DigestValue>dGVzdA==</ds:DigestValue>
                 </ds:Reference>
              </ds:SignedInfo>
              <ds:SignatureValue>dGVzdA==</ds:SignatureValue>
           </ds:Signature>
        </smd:signedMark>
        """;

    XMLSignatureException e =
        assertThrows(
            XMLSignatureException.class, () -> tmchXmlSignature.verify(xml.getBytes(UTF_8)));
    assertThat(e)
        .hasMessageThat()
        .contains("Signature Reference URI does not match the root element ID");
  }

  @Test
  void testVerify_multipleSignedMarks_fails() {
    // Even if the signature does validate the root signed mark, it's sketchy at best to include
    // another signed mark hidden in the XML. Don't allow it.
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <smd:signedMark xmlns:smd="urn:ietf:params:xml:ns:signedMark-1.0" id="id-1">
           <smd:id>id-1</smd:id>
           <ds:Signature xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
              <ds:SignedInfo>
                 <ds:CanonicalizationMethod Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"/>
                 <ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"/>
                 <ds:Reference URI="#id-1">
                    <ds:Transforms>
                       <ds:Transform Algorithm=\
                       "http://www.w3.org/2000/09/xmldsig#enveloped-signature"/>
                    </ds:Transforms>
                    <ds:DigestMethod Algorithm="http://www.w3.org/2001/04/xmlenc#sha256"/>
                    <ds:DigestValue>dGVzdA==</ds:DigestValue>
                 </ds:Reference>
              </ds:SignedInfo>
              <ds:SignatureValue>dGVzdA==</ds:SignatureValue>
              <ds:Object>
                 <smd:signedMark id="id-2">
                    <smd:id>id-2</smd:id>
                 </smd:signedMark>
              </ds:Object>
           </ds:Signature>
        </smd:signedMark>
        """;

    XMLSignatureException e =
        assertThrows(
            XMLSignatureException.class, () -> tmchXmlSignature.verify(xml.getBytes(UTF_8)));
    assertThat(e)
        .hasMessageThat()
        .contains("Expected exactly one <smd:signedMark> element in the document");
  }
}
