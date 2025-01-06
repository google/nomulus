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

package google.registry.model.mark;

import google.registry.model.ImmutableObject;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlTransient;

/**
 * Common fields shared amongst all mark contact subclasses.
 *
 * @see MarkContact
 * @see MarkHolder
 */
@XmlTransient
public class CommonMarkContactFields extends ImmutableObject {

  /** Name of the contact. */
  String name;

  /** Name of the contact's organization. */
  @XmlElement(name = "org")
  String organization;

  /** Contact's address information. */
  @XmlElement(name = "addr")
  MarkAddress address;

  /** Contact's voice telephone number. */
  MarkPhoneNumber voice;

  /** Contact's fax telephone number. */
  MarkPhoneNumber fax;

  /** Contact's email. */
  String email;

  public String getName() {
    return name;
  }

  public String getOrganization() {
    return organization;
  }

  public MarkAddress getAddress() {
    return address;
  }

  public MarkPhoneNumber getVoice() {
    return voice;
  }

  public MarkPhoneNumber getFax() {
    return fax;
  }

  public String getEmail() {
    return email;
  }
}
