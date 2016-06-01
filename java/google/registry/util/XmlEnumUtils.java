// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

import com.google.common.collect.ImmutableMap;

import java.util.Arrays;

import javax.xml.bind.annotation.XmlEnumValue;

/** Utility methods related to xml enums. */
public class XmlEnumUtils {
  /** Read the {@link XmlEnumValue} string off of an enum. */
  public static String enumToXml(Enum<?> input) {
    try {
      return input.getClass().getField(input.name()).getAnnotation(XmlEnumValue.class).value();
    } catch (NoSuchFieldException e) {
      throw new RuntimeException(e);
    }
  }

  /** Efficient lookup from xml enums to java enums */
  public final static class XmlToEnumMapper<T extends Enum<?>> {

    private final ImmutableMap<String, T> map;

    public XmlToEnumMapper(T[] enumValues) {
      this(Arrays.asList(enumValues));
    }

    public XmlToEnumMapper(Iterable<T> enumValues) {
      ImmutableMap.Builder<String, T> mapBuilder = new ImmutableMap.Builder<>();
      for (T value : enumValues) {
        try {
          String xmlName =
              value.getClass().getField(value.name()).getAnnotation(XmlEnumValue.class).value();
          mapBuilder = mapBuilder.put(xmlName, value);
        } catch (NoSuchFieldException e) {
          throw new RuntimeException(e);
        }
      }
      map = mapBuilder.build();
    }

    /** Look up {@link T} from the {@link XmlEnumValue} */
    public T xmlToEnum(String value) {
      return map.get(value);
    }
  }
}
