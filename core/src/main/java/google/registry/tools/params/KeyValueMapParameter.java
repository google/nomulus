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

package google.registry.tools.params;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import google.registry.model.console.RegistrarRole;
import java.util.Map;
import org.joda.money.CurrencyUnit;

/**
 * Combined converter and validator class for key-value map JCommander argument strings.
 *
 * <p>These strings have the form {@code <K-str>=<V-str>,[<K-str>=<V-str>]*} where
 * {@code <K-str>} and {@code <V-str>} are strings that can be parsed into instances of some key
 * type {@code K} and value type {@code V}, respectively. This class converts a string into an
 * ImmutableMap mapping {@code K} to {@code V}. Validation and conversion share the same logic;
 * validation is just done by attempting conversion and throwing exceptions if need be.
 *
 * <p>Subclasses must implement parseKey() and parseValue() to define how to parse {@code <K-str>}
 * and {@code <V-str>} into {@code K} and {@code V}, respectively.
 *
 * @param <K> instance key type
 * @param <V> instance value type
 */
public abstract class KeyValueMapParameter<K, V>
    extends ParameterConverterValidator<ImmutableMap<K, V>> {

  public KeyValueMapParameter(String messageForInvalid) {
    super(messageForInvalid);
  }

  public KeyValueMapParameter() {
    super("Not formatted correctly.");
  }

  /** Override to define how to parse rawKey into an object of type K. */
  protected abstract K parseKey(String rawKey);

  /** Override to define how to parse rawValue into an object of type V. */
  protected abstract V parseValue(String rawValue);

  /** Override to perform any post-processing on the map. */
  protected ImmutableMap<K, V> processMap(ImmutableMap<K, V> map) {
    return map;
  }

  @Override
  public final ImmutableMap<K, V> convert(String keyValueMapString) {
    ImmutableMap.Builder<K, V> builder = new ImmutableMap.Builder<>();
    if (!Strings.isNullOrEmpty(keyValueMapString)) {
      for (Map.Entry<String, String> entry :
          Splitter.on(',').withKeyValueSeparator('=').split(keyValueMapString).entrySet()) {
        builder.put(parseKey(entry.getKey()), parseValue(entry.getValue()));
      }
    }
    return processMap(builder.build());
  }

  /** Combined converter and validator class for string-to-string Map argument strings. */
  public static class StringToStringMap extends KeyValueMapParameter<String, String> {
    @Override
    protected String parseKey(String rawKey) {
      return rawKey;
    }

    @Override
    protected String parseValue(String value) {
      return value;
    }
  }

  /** Combined converter and validator class for string-to-integer Map argument strings. */
  public static class StringToIntegerMap extends KeyValueMapParameter<String, Integer> {
    @Override
    protected String parseKey(String rawKey) {
      return rawKey;
    }

    @Override
    protected Integer parseValue(String value) {
      return Integer.parseInt(value);
    }
  }

  /** Combined converter and validator class for currency unit-to-string Map argument strings. */
  public static class CurrencyUnitToStringMap extends KeyValueMapParameter<CurrencyUnit, String> {
    @Override
    protected CurrencyUnit parseKey(String rawKey) {
      return CurrencyUnit.of(rawKey);
    }

    @Override
    protected String parseValue(String value) {
      return value;
    }
  }

  /** Combined converter/validator class for maps of registrar names to registrar roles. */
  public static class StringToRegistrarRoleMap extends KeyValueMapParameter<String, RegistrarRole> {

    @Override
    protected String parseKey(String rawKey) {
      return rawKey;
    }

    @Override
    protected RegistrarRole parseValue(String rawValue) {
      return RegistrarRole.valueOf(rawValue);
    }
  }
}
