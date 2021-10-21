// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.util.CollectionUtils.entriesToImmutableMap;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.model.common.TimedTransitionProperty.TimedTransition;
import google.registry.persistence.converter.StringMapDescriptor.StringMap;
import java.io.Serializable;
import java.util.Map;
import javax.annotation.Nullable;
import javax.persistence.AttributeConverter;
import org.joda.time.DateTime;

/**
 * Base JPA converter for {@link TimedTransitionProperty} objects that are stored in a column with
 * data type of hstore in the database.
 */
public abstract class TimedTransitionPropertyConverterBase<
        K extends Serializable, V extends TimedTransition<K>>
    implements AttributeConverter<TimedTransitionProperty<K, V>, StringMap> {

  abstract Map.Entry<String, String> convertToDatabaseMapEntry(Map.Entry<DateTime, V> entry);

  abstract Map.Entry<DateTime, K> convertToEntityMapEntry(Map.Entry<String, String> entry);

  abstract Class<V> getTimedTransitionSubclass();

  @Override
  public StringMap convertToDatabaseColumn(@Nullable TimedTransitionProperty<K, V> attribute) {
    return attribute == null
        ? null
        : StringMap.create(
            attribute.entrySet().stream()
                .map(this::convertToDatabaseMapEntry)
                .collect(entriesToImmutableMap()));
  }

  @Override
  public TimedTransitionProperty<K, V> convertToEntityAttribute(@Nullable StringMap dbData) {
    if (dbData == null) {
      return null;
    }
    ImmutableMap<DateTime, K> map =
        dbData.getMap().entrySet().stream()
            .map(this::convertToEntityMapEntry)
            .collect(entriesToImmutableMap());
    return TimedTransitionProperty.fromValueMap(
        ImmutableSortedMap.copyOf(map), getTimedTransitionSubclass());
  }
}
