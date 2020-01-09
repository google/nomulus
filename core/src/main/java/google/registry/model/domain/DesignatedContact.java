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

import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.Index;
import google.registry.model.ImmutableObject;
import google.registry.model.contact.ContactResource;
import javax.persistence.Embeddable;
import javax.xml.bind.annotation.XmlEnumValue;

/**
 * Persisted type for storing a domain's contact associations.
 *
 * <p>A contact association on a domain consists of the contact key and the contact "type", which is
 * the designated role of this contact with respect to this domain. When converting to and from EPP
 * XML, we use {@link ForeignKeyedDesignatedContact} to replace the contact's Datastore key with its
 * foreign key, since that is what EPP exposes.
 *
 * <p>Note one could in principle store contact foreign keys here in addition to keys, unlike the
 * situation with hosts where client-side renames would make that data stale. However, we sometimes
 * rename contacts internally ourselves, and it's easier to use the same model for both cases.
 *
 * @see <a href="http://tools.ietf.org/html/rfc5731#section-2.2">RFC 5731 - EPP Domain Name Mapping
 *     - Contact and Client Identifiers</a>
 */
@Embed
@Embeddable
public class DesignatedContact extends ImmutableObject {

  /**
   * XML type for contact types. This can be either: {@code "admin"}, {@code "billing"}, or
   * {@code "tech"} and corresponds to {@code contactAttrType} in {@code domain-1.0.xsd}.
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

  public static DesignatedContact create(Type type, Key<ContactResource> contact) {
    DesignatedContact instance = new DesignatedContact();
    instance.type = type;
    instance.contact = checkArgumentNotNull(contact, "Must specify contact key");
    return instance;
  }

  Type type;

  @Index Key<ContactResource> contact;

  public Type getType() {
    return type;
  }

  public Key<ContactResource> getContactKey() {
    return contact;
  }
}
