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

package google.registry.model.smd;

import google.registry.model.ImmutableObject;
import google.registry.model.mark.Mark;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementRef;
import jakarta.xml.bind.annotation.XmlRootElement;
import org.joda.time.DateTime;

/**
 * Represents an XML fragment that is digitally signed by the TMCH to prove ownership over a mark.
 **/
@XmlRootElement(name = "signedMark")
public class SignedMark extends ImmutableObject implements AbstractSignedMark {

  /** XSD ID for use with an IDREF URI from the Signature element. */
  @XmlAttribute(name = "id")
  String xsdId;

  /**
   * Signed mark identifier. This is a concatenation of the local identifier, followed by a hyphen,
   * followed by the issuer identifier.
   */
  String id;

  /** Information of the issuer of the mark registration. */
  IssuerInfo issuerInfo;

  /** Creation time of this signed mark. */
  @XmlElement(name = "notBefore")
  DateTime creationTime;

  /** Expiration time of this signed mark. */
  @XmlElement(name = "notAfter")
  DateTime expirationTime;

  /** Mark information. */
  @XmlElementRef(type = Mark.class)
  Mark mark;

  /**
   * Digital signature of the signed mark. Note that we don't unmarshal this data, as there is
   * already an existing Java library to handle validation of this signature.
   *
   * @see javax.xml.crypto.dsig.XMLSignature
   */
  @XmlElement(name = "Signature", namespace = "http://www.w3.org/2000/09/xmldsig#")
  Object xmlSignature;

  public String getId() {
    return id;
  }

  public DateTime getCreationTime() {
    return creationTime;
  }

  public DateTime getExpirationTime() {
    return expirationTime;
  }

  public Mark getMark() {
    return mark;
  }

  public boolean hasSignature() {
    return xmlSignature != null;
  }
}
