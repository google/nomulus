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

package google.registry.model.adapters;

import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.adapters.XmlAdapter;

/**
 * Used by JAXB to convert enums to the peculiar EPP format that puts the value in an attribute.
 *
 * @param <E> the enum type
 */
public class EnumToAttributeAdapter<E extends Enum<E> & EnumToAttributeAdapter.EppEnum>
    extends XmlAdapter<EnumToAttributeAdapter.EnumShim, E> {

  /** Interface for epp enums that can be transformed with this adapter. */
  public interface EppEnum {
    String getXmlName();
  }

  /**
   * EPP's peculiar enum format.
   *
   * <p>It's meant to allow more information inside the element, but it's an annoyance when we want
   * to deal with pure enums.
   */
  static class EnumShim {
    @XmlAttribute
    String s;
  }

  // Enums that can be unmarshalled from input can override this.
  @Override
  public E unmarshal(EnumShim shim) {
    throw new UnsupportedOperationException();
  }

  @Override
  public final EnumShim marshal(E enumeration) {
    EnumShim shim = new EnumShim();
    shim.s = enumeration.getXmlName();
    return shim;
  }
}
