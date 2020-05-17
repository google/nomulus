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

package google.registry.model.eppcommon;

import static com.google.common.base.Preconditions.checkNotNull;

import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import javax.persistence.Embeddable;
import javax.persistence.MappedSuperclass;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.XmlValue;
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

/**
 * Container for generic E164 phone number.
 *
 * <p>This is the "e164" type from <a href="http://tools.ietf.org/html/rfc5733">RFC5733</a>. It also
 * matches the "e164Type" type from <a href="http://tools.ietf.org/html/draft-lozano-tmch-smd">Mark
 * and Signed Mark Objects Mapping</a>
 *
 * <blockquote>
 *
 * <p>"Contact telephone number structure is derived from structures defined in [ITU.E164.2005].
 * Telephone numbers described in this mapping are character strings that MUST begin with a plus
 * sign ("+", ASCII value 0x002B), followed by a country code defined in [ITU.E164.2005], followed
 * by a dot (".", ASCII value 0x002E), followed by a sequence of digits representing the telephone
 * number. An optional "x" attribute is provided to note telephone extension information."
 *
 * </blockquote>
 *
 * @see google.registry.model.contact.ContactPhoneNumber
 * @see google.registry.model.mark.MarkPhoneNumber
 */
@XmlTransient
@Embeddable
@MappedSuperclass
public class PhoneNumber extends ImmutableObject {

  @XmlValue
  @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
  String phoneNumber;

  @XmlAttribute(name = "x")
  @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
  String extension;

  public String getPhoneNumber() {
    return phoneNumber;
  }

  public String getExtension() {
    return extension;
  }

  public String toPhoneString() {
    return phoneNumber + (extension != null ? " x" + extension : "");
  }

  /** A builder for constructing {@link PhoneNumber}. */
  public static class Builder<T extends PhoneNumber> extends Buildable.Builder<T> {
    @Override
    public T build() {
      checkNotNull(getInstance().phoneNumber, "phoneNumber");
      return super.build();
    }

    public Builder<T> setPhoneNumber(String phoneNumber) {
      getInstance().phoneNumber = phoneNumber;
      return this;
    }

    public Builder<T> setExtension(String extension) {
      getInstance().extension = extension;
      return this;
    }
  }
}
