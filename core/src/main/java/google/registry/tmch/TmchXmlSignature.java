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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Throwables.getRootCause;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static google.registry.xml.XmlTransformer.loadXmlSchemas;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.xml.crypto.AlgorithmMethod;
import javax.xml.crypto.KeySelector;
import javax.xml.crypto.KeySelectorException;
import javax.xml.crypto.KeySelectorResult;
import javax.xml.crypto.MarshalException;
import javax.xml.crypto.XMLCryptoContext;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.validation.Schema;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Helper class for verifying TMCH certificates and XML signatures. */
@ThreadSafe
public class TmchXmlSignature {

  @VisibleForTesting
  final TmchCertificateAuthority tmchCertificateAuthority;

  @Inject
  public TmchXmlSignature(TmchCertificateAuthority tmchCertificateAuthority) {
    this.tmchCertificateAuthority = tmchCertificateAuthority;
  }

  private static final Schema SCHEMA =
      loadXmlSchemas(ImmutableList.of("mark.xsd", "dsig.xsd", "smd.xsd"));

  /**
   * Verifies that signed mark data contains a valid signature.
   *
   * <p>This method DOES NOT check if the SMD ID is revoked. It's only concerned with the
   * cryptographic stuff.
   *
   * @throws GeneralSecurityException for unsupported protocols, certs not signed by the TMCH,
   *     incorrect keys, and for invalid, old, not-yet-valid or revoked certificates.
   */
  public void verify(byte[] smdXml)
      throws GeneralSecurityException, IOException, MarshalException, ParserConfigurationException,
          SAXException, XMLSignatureException {
    checkArgument(smdXml.length > 0);
    Document doc = parseSmdDocument(new ByteArrayInputStream(smdXml));

    NodeList signatureNodes = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
    if (signatureNodes.getLength() != 1) {
      throw new XMLSignatureException("Expected exactly one <ds:Signature> element.");
    }
    XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
    KeyValueKeySelector selector = new KeyValueKeySelector(tmchCertificateAuthority);
    DOMValidateContext context = new DOMValidateContext(selector, signatureNodes.item(0));
    XMLSignature signature = factory.unmarshalXMLSignature(context);

    boolean isValid;
    try {
      isValid = signature.validate(context);
    } catch (XMLSignatureException e) {
      throwIfInstanceOf(getRootCause(e), GeneralSecurityException.class);
      throw e;
    }
    if (!isValid) {
      throw new XMLSignatureException(explainValidationProblem(context, signature));
    }
  }

  private static Document parseSmdDocument(InputStream input)
      throws SAXException, IOException, ParserConfigurationException {
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    dbf.setSchema(SCHEMA);
    dbf.setAttribute("http://apache.org/xml/features/validation/schema/normalized-value", false);
    dbf.setNamespaceAware(true);
    return dbf.newDocumentBuilder().parse(input);
  }

  private static String explainValidationProblem(
      DOMValidateContext context, XMLSignature signature)
          throws XMLSignatureException {
    @SuppressWarnings("unchecked")  // Safe by specification.
    List<Reference> references = signature.getSignedInfo().getReferences();
    StringBuilder builder = new StringBuilder();
    builder.append("Signature failed core validation\n");
    boolean sv = signature.getSignatureValue().validate(context);
    builder.append(String.format("Signature validation status: %s\n", sv));
    for (Reference ref : references) {
      builder.append("references[");
      builder.append(ref.getURI());
      builder.append("] validity status: ");
      builder.append(ref.validate(context));
      builder.append("\n");
    }
    return builder.toString();
  }

  /** Callback class for DOM validator checks validity of {@code <ds:KeyInfo>} elements. */
  private static final class KeyValueKeySelector extends KeySelector {

    private final TmchCertificateAuthority tmchCertificateAuthority;

    KeyValueKeySelector(TmchCertificateAuthority tmchCertificateAuthority) {
      this.tmchCertificateAuthority = tmchCertificateAuthority;
    }

    @Nullable
    @Override
    public KeySelectorResult select(
        @Nullable KeyInfo keyInfo,
        KeySelector.Purpose purpose,
        AlgorithmMethod method,
        XMLCryptoContext context)
        throws KeySelectorException {
      if (keyInfo == null) {
        return null;
      }
      for (Object keyInfoChild : keyInfo.getContent()) {
        if (keyInfoChild instanceof X509Data) {
          X509Data x509Data = (X509Data) keyInfoChild;
          for (Object x509DataChild : x509Data.getContent()) {
            if (x509DataChild instanceof X509Certificate) {
              X509Certificate cert = (X509Certificate) x509DataChild;
              try {
                tmchCertificateAuthority.verify(cert);
              } catch (SignatureException e) {
                throw new KeySelectorException(new CertificateSignatureException(e.getMessage()));
              } catch (GeneralSecurityException e) {
                throw new KeySelectorException(e);
              }
              return new SimpleKeySelectorResult(cert.getPublicKey());
            }
          }
        }
      }
      throw new KeySelectorException("No public key found.");
    }
  }

  /** @see TmchXmlSignature.KeyValueKeySelector */
  private static class SimpleKeySelectorResult implements KeySelectorResult {
    private PublicKey publicKey;

    SimpleKeySelectorResult(PublicKey publicKey) {
      this.publicKey = checkNotNull(publicKey, "publicKey");
    }

    @Override
    public java.security.Key getKey() {
      return publicKey;
    }
  }

  /** CertificateException wrapper. */
  public static class CertificateSignatureException extends CertificateException {
    public CertificateSignatureException(String message) {
      super(message);
    }
  }
}
