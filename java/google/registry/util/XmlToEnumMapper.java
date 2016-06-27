package google.registry.util;

import com.google.common.collect.ImmutableMap;

import javax.xml.bind.annotation.XmlEnumValue;

/** Efficient lookup from xml enums to java enums */
public final class XmlToEnumMapper<T extends Enum<?>> {

  private final ImmutableMap<String, T> map;

  /** Look up {@link T} from the {@link XmlEnumValue} */
  public T xmlToEnum(String value) {
    return map.get(value);
  }

  /**
   * Creates a new {@link XmlToEnumMapper} from xml value to enum value.
   */
  public static <T extends Enum<?>> XmlToEnumMapper<T> create(T[] enumValues) {
    return new XmlToEnumMapper<T>(enumValues);
  }

  private XmlToEnumMapper(T[] enumValues) {
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
}
