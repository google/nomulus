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

import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

/** Contact information for the representative of the mark. */
public class MarkContact extends CommonMarkContactFields {

  /** The type of the contact that represents the mark. */
  @XmlEnum
  enum ContactType {
    @XmlEnumValue("owner")
    OWNER,

    @XmlEnumValue("agent")
    AGENT,

    @XmlEnumValue("thirdParty")
    THIRD_PARTY
  }

  @XmlAttribute
  ContactType type;

  public ContactType getType() {
    return type;
  }
}
