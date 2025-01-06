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

package google.registry.model.domain;

import google.registry.model.ImmutableObject;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlValue;

/**
 * EPP-XML-serializable equivalent of {@link DesignatedContact}.
 *
 * <p>This type is used on the wire for EPP XML, where only the contact ID (foreign key) is exposed.
 * This is converted to and from the persisted type, {@link DesignatedContact}, which stores the
 * primary key instead of the foreign key.
 *
 * @see <a href="http://tools.ietf.org/html/rfc5731#section-2.2">RFC 5731 - EPP Domain Name Mapping
 *     - Contact and Client Identifiers</a>
 */
public class ForeignKeyedDesignatedContact extends ImmutableObject {
  @XmlAttribute(required = true)
  DesignatedContact.Type type;

  @XmlValue
  String contactId;

  public static ForeignKeyedDesignatedContact create(
      DesignatedContact.Type type, String contactId) {
    ForeignKeyedDesignatedContact instance = new ForeignKeyedDesignatedContact();
    instance.type = type;
    instance.contactId = contactId;
    return instance;
  }
}
