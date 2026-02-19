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

package google.registry.model.domain;

import google.registry.model.ImmutableObject;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlEnumValue;
import jakarta.xml.bind.annotation.XmlValue;

/**
 * Vestigial EPP-XML-serializable equivalent of a contact.
 *
 * <p>This type was used on the wire for EPP XML, where only the contact ID (foreign key) was
 * exposed.
 *
 * @see <a href="http://tools.ietf.org/html/rfc5731#section-2.2">RFC 5731 - EPP Domain Name Mapping
 *     - Contact and Client Identifiers</a>
 */
public class ForeignKeyedDesignatedContact extends ImmutableObject {

  /**
   * XML type for contact types. This can be either: {@code "admin"}, {@code "billing"}, or {@code
   * "tech"} and corresponds to {@code contactAttrType} in {@code domain-1.0.xsd}.
   */
  public enum Type {
    @XmlEnumValue("admin")
    ADMIN,
    @XmlEnumValue("billing")
    BILLING,
    @XmlEnumValue("tech")
    TECH,
    /** The registrant type is not reflected in XML and exists only for internal use. */
    REGISTRANT
  }

  @XmlAttribute(required = true)
  Type type;

  @XmlValue
  String contactId;

  public static ForeignKeyedDesignatedContact create(Type type, String contactId) {
    ForeignKeyedDesignatedContact instance = new ForeignKeyedDesignatedContact();
    instance.type = type;
    instance.contactId = contactId;
    return instance;
  }
}
