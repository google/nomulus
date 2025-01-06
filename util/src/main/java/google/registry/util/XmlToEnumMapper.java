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

package google.registry.util;

import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.collect.ImmutableMap;
import jakarta.xml.bind.annotation.XmlEnumValue;

/** Efficient lookup from xml enums to java enums */
public final class XmlToEnumMapper<T extends Enum<?>> {

  private final ImmutableMap<String, T> map;

  /** Look up T from the {@link XmlEnumValue} */
  public T xmlToEnum(String value) {
    return map.get(value);
  }

  /**
   * Creates a new {@link XmlToEnumMapper} from xml value to enum value.
   */
  public static <T extends Enum<?>> XmlToEnumMapper<T> create(T[] enumValues) {
    return new XmlToEnumMapper<>(enumValues);
  }

  private XmlToEnumMapper(T[] enumValues) {
    ImmutableMap.Builder<String, T> mapBuilder = new ImmutableMap.Builder<>();
    for (T value : enumValues) {
      try {
        XmlEnumValue xmlAnnotation = value
            .getDeclaringClass()
            .getField(value.name())
            .getAnnotation(XmlEnumValue.class);
        checkArgumentNotNull(xmlAnnotation, "Cannot map enum value to xml name: " + value);
        String xmlName = xmlAnnotation.value();
        mapBuilder = mapBuilder.put(xmlName, value);
      } catch (NoSuchFieldException e) {
        throw new RuntimeException(e);
      }
    }
    map = mapBuilder.build();
  }
}
