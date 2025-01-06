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

package google.registry.persistence.converter;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import google.registry.model.common.TimedTransitionProperty;
import java.io.Serializable;
import java.util.Map;
import org.joda.time.DateTime;

/**
 * Base Hibernate custom type for {@link TimedTransitionProperty}.
 *
 * @param <V> Type parameter for value.
 */
public abstract class TimedTransitionBaseUserType<V extends Serializable>
    extends MapUserType<TimedTransitionProperty<V>> {

  abstract String valueToString(V value);

  abstract V stringToValue(String string);

  @SuppressWarnings("unchecked")
  @Override
  public Class<TimedTransitionProperty<V>> returnedClass() {
    return (Class<TimedTransitionProperty<V>>) ((Object) TimedTransitionProperty.class);
  }

  @Override
  Map<String, String> toStringMap(TimedTransitionProperty<V> map) {
    return map.toValueMap().entrySet().stream()
        .collect(toImmutableMap(e -> e.getKey().toString(), e -> valueToString(e.getValue())));
  }

  @Override
  TimedTransitionProperty<V> toEntity(Map<String, String> map) {
    ImmutableSortedMap<DateTime, V> valueMap =
        map.entrySet().stream()
            .collect(
                toImmutableSortedMap(
                    Ordering.natural(),
                    e -> DateTime.parse(e.getKey()),
                    e -> stringToValue(e.getValue())));
    return TimedTransitionProperty.fromValueMap(valueMap);
  }
}
