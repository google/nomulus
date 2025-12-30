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

package google.registry.flows.domain;

import static com.google.common.collect.Iterables.concat;
import static google.registry.flows.FlowUtils.unmarshalEpp;

import com.google.common.collect.ImmutableList;
import google.registry.flows.EppException;
import google.registry.flows.EppException.ParameterValuePolicyErrorException;
import google.registry.flows.EppException.ParameterValueSyntaxErrorException;
import google.registry.flows.EppException.RequiredParameterMissingException;
import google.registry.model.mark.Mark;
import google.registry.model.mark.ProtectedMark;
import google.registry.model.mark.Trademark;
import google.registry.model.smd.AbstractSignedMark;
import google.registry.model.smd.EncodedSignedMark;
import google.registry.model.smd.SignedMark;
import google.registry.model.smd.SignedMarkRevocationList;
import google.registry.tmch.TmchXmlSignature;
import google.registry.tmch.TmchXmlSignature.CertificateSignatureException;
import jakarta.inject.Inject;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SignatureException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertificateRevokedException;
import javax.xml.crypto.MarshalException;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.parsers.ParserConfigurationException;
import org.joda.time.DateTime;
import org.xml.sax.SAXException;

/** TMCH utility functions for domain flows. */
public final class DomainFlowTmchUtils {

  private final TmchXmlSignature tmchXmlSignature;

  @Inject
  public DomainFlowTmchUtils(TmchXmlSignature tmchXmlSignature) {
    this.tmchXmlSignature = tmchXmlSignature;
  }

  public SignedMark verifySignedMarks(
      String tld, ImmutableList<AbstractSignedMark> signedMarks, String domainLabel, DateTime now)
      throws EppException {
    if (signedMarks.size() > 1) {
      throw new TooManySignedMarksException();
    }
    if (!(signedMarks.get(0) instanceof EncodedSignedMark)) {
      throw new SignedMarksMustBeEncodedException();
    }
    SignedMark signedMark =
        verifyEncodedSignedMark(tld, (EncodedSignedMark) signedMarks.get(0), now);
    return verifySignedMarkValidForDomainLabel(signedMark, domainLabel);
  }

  public SignedMark verifySignedMarkValidForDomainLabel(SignedMark signedMark, String domainLabel)
      throws NoMarksFoundMatchingDomainException {
    if (!containsMatchingLabel(signedMark.getMark(), domainLabel)) {
      throw new NoMarksFoundMatchingDomainException();
    }
    return signedMark;
  }

  // TODO(b/412715713): remove the tld parameter when RST completes.
  public SignedMark verifyEncodedSignedMark(
      String tld, EncodedSignedMark encodedSignedMark, DateTime now) throws EppException {
    if (!encodedSignedMark.getEncoding().equals("base64")) {
      throw new Base64RequiredForEncodedSignedMarksException();
    }
    byte[] signedMarkData;
    try {
      signedMarkData = encodedSignedMark.getBytes();
    } catch (IllegalStateException e) {
      throw new SignedMarkEncodingErrorException();
    }

    SignedMark signedMark;
    try {
      signedMark = unmarshalEpp(SignedMark.class, signedMarkData);
    } catch (EppException e) {
      throw new SignedMarkParsingErrorException();
    }

    if (SignedMarkRevocationList.get(tld).isSmdRevoked(signedMark.getId(), now)) {
      throw new SignedMarkRevokedErrorException();
    }

    try {
      tmchXmlSignature.verify(signedMarkData);
    } catch (CertificateExpiredException e) {
      throw new SignedMarkCertificateExpiredException();
    } catch (CertificateNotYetValidException e) {
      throw new SignedMarkCertificateNotYetValidException();
    } catch (CertificateRevokedException e) {
      throw new SignedMarkCertificateRevokedException();
    } catch (CertificateSignatureException e) {
      throw new SignedMarkCertificateSignatureException();
    } catch (SignatureException | XMLSignatureException e) {
      throw new SignedMarkSignatureException();
    } catch (GeneralSecurityException e) {
      throw new SignedMarkCertificateInvalidException();
    } catch (IOException
        | MarshalException
        | SAXException
        | ParserConfigurationException e) {
      throw new SignedMarkParsingErrorException();
    }

    if (now.isBefore(signedMark.getCreationTime())) {
      throw new FoundMarkNotYetValidException();
    }

    if (now.isAfter(signedMark.getExpirationTime())) {
      throw new FoundMarkExpiredException();
    }

    return signedMark;
  }

  /** Returns true if the mark contains a valid claim that matches the label. */
  private static boolean containsMatchingLabel(Mark mark, String label) {
    for (Trademark trademark : mark.getTrademarks()) {
      if (trademark.getLabels().contains(label)) {
        return true;
      }
    }
    for (ProtectedMark protectedMark
        : concat(mark.getTreatyOrStatuteMarks(), mark.getCourtMarks())) {
      if (protectedMark.getLabels().contains(label)) {
        return true;
      }
    }
    return false;
  }

  /** Encoded signed marks must use base64 encoding. */
  static class Base64RequiredForEncodedSignedMarksException
      extends ParameterValuePolicyErrorException {
    public Base64RequiredForEncodedSignedMarksException() {
      super("Encoded signed marks must use base64 encoding");
    }
  }

  /** The provided mark does not match the desired domain label. */
  static class NoMarksFoundMatchingDomainException extends RequiredParameterMissingException {
    public NoMarksFoundMatchingDomainException() {
      super("The provided mark does not match the desired domain label");
    }
  }

  /** The provided mark is not yet valid. */
  static class FoundMarkNotYetValidException extends ParameterValuePolicyErrorException {
    public FoundMarkNotYetValidException() {
      super("The provided mark is not yet valid");
    }
  }

  /** The provided mark has expired. */
  static class FoundMarkExpiredException extends ParameterValuePolicyErrorException {
    public FoundMarkExpiredException() {
      super("The provided mark has expired");
    }
  }

  /** Certificate used in signed mark signature was revoked by ICANN. */
  static class SignedMarkCertificateRevokedException extends ParameterValuePolicyErrorException {
    public SignedMarkCertificateRevokedException() {
      super("Signed mark certificate was revoked");
    }
  }

  /** Certificate used in signed mark signature has expired. */
  static class SignedMarkCertificateNotYetValidException
      extends ParameterValuePolicyErrorException {
    public SignedMarkCertificateNotYetValidException() {
      super("Signed mark certificate not yet valid");
    }
  }

  /** Certificate used in signed mark signature has expired. */
  static class SignedMarkCertificateExpiredException extends ParameterValuePolicyErrorException {
    public SignedMarkCertificateExpiredException() {
      super("Signed mark certificate has expired");
    }
  }

  /** Certificate parsing error, or possibly a bad provider or algorithm. */
  static class SignedMarkCertificateInvalidException extends ParameterValuePolicyErrorException {
    public SignedMarkCertificateInvalidException() {
      super("Signed mark certificate is invalid");
    }
  }

  /** Invalid signature on a signed mark. */
  static class SignedMarkCertificateSignatureException extends ParameterValuePolicyErrorException {
    public SignedMarkCertificateSignatureException() {
      super("Signed mark certificate not signed by ICANN");
    }
  }

  /** Invalid signature on a signed mark. */
  static class SignedMarkSignatureException extends ParameterValuePolicyErrorException {
    public SignedMarkSignatureException() {
      super("Signed mark signature is invalid");
    }
  }

  /** Signed marks must be encoded. */
  static class SignedMarksMustBeEncodedException extends ParameterValuePolicyErrorException {
    public SignedMarksMustBeEncodedException() {
      super("Signed marks must be encoded");
    }
  }

  /** Only one signed mark is allowed per application. */
  static class TooManySignedMarksException extends ParameterValuePolicyErrorException {
    public TooManySignedMarksException() {
      super("Only one signed mark is allowed per application");
    }
  }

  /** Signed mark data is revoked. */
  static class SignedMarkRevokedErrorException extends ParameterValuePolicyErrorException {
    public SignedMarkRevokedErrorException() {
      super("SMD has been revoked");
    }
  }

  /** Error while parsing encoded signed mark data. */
  static class SignedMarkParsingErrorException extends ParameterValueSyntaxErrorException {
    public SignedMarkParsingErrorException() {
      super("Error while parsing encoded signed mark data");
    }
  }

  /** Signed mark data is improperly encoded. */
  static class SignedMarkEncodingErrorException extends ParameterValueSyntaxErrorException {
    public SignedMarkEncodingErrorException() {
      super("Signed mark data is improperly encoded");
    }
  }

}
