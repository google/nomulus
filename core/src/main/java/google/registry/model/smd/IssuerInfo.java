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
import google.registry.model.mark.MarkPhoneNumber;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;

/** Holds information about the issuer of a mark registration. */
public class IssuerInfo extends ImmutableObject {

  @XmlAttribute(name = "issuerID")
  String issuerId;

  /** The issuer identifier. */
  @XmlElement(name = "issuerID")
  String id;

  /** The organization name of the issuer. */
  @XmlElement(name = "org")
  String organization;

  /** Issuer customer support email address. */
  String email;

  /** The HTTP URL of the issuer's site. */
  String url;

  /** The issuer's voice telephone number. */
  MarkPhoneNumber voice;

  public String getIssuerId() {
    return issuerId;
  }

  public String getId() {
    return id;
  }

  public String getOrganization() {
    return organization;
  }

  public String getEmail() {
    return email;
  }

  public String getUrl() {
    return url;
  }

  public MarkPhoneNumber getVoice() {
    return voice;
  }
}
