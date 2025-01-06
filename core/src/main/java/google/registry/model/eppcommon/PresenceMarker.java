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

package google.registry.model.eppcommon;

import google.registry.model.ImmutableObject;
import jakarta.persistence.Embeddable;
import jakarta.xml.bind.annotation.XmlTransient;
import java.io.Serializable;

/**
 * Used as the value of a tag that is present in the XML but has no children or value.
 *
 * <p>When placed in a field "foo", this will correctly unmarshal from both {@code <foo/>} and
 * {@code <foo></foo>}, and will unmarshal always to {@code <foo/>}.
 */
@Embeddable
public class PresenceMarker extends ImmutableObject implements Serializable {
  @XmlTransient
  boolean marked = true;
}
