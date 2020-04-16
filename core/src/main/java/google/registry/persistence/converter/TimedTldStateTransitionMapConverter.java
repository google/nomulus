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

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.model.registry.Registry.TldState;
import google.registry.model.registry.Registry.TldStateTransition;
import google.registry.persistence.converter.StringMapDescriptor.StringMap;
import java.util.Map;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import org.joda.time.DateTime;

/**
 * JPA converter for storing/retrieving {@link TimedTransitionProperty<TldState,
 * TldStateTransition>} objects.
 */
@Converter(autoApply = true)
public class TimedTldStateTransitionMapConverter
    implements AttributeConverter<
        TimedTransitionProperty<TldState, TldStateTransition>, StringMap> {

  @Override
  public StringMap convertToDatabaseColumn(
      TimedTransitionProperty<TldState, TldStateTransition> attribute) {
    return attribute == null
        ? null
        : StringMap.create(
            attribute.entrySet().stream()
                .map(
                    entry ->
                        Maps.immutableEntry(
                            entry.getKey().toString(), entry.getValue().getValue().name()))
                .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
  }

  @Override
  public TimedTransitionProperty<TldState, TldStateTransition> convertToEntityAttribute(
      StringMap dbData) {
    if (dbData == null) {
      return null;
    }
    Map map =
        dbData.getMap().entrySet().stream()
            .map(
                entry ->
                    Maps.immutableEntry(
                        DateTime.parse(entry.getKey()), TldState.valueOf(entry.getValue())))
            .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
    return TimedTransitionProperty.fromValueMap(
        ImmutableSortedMap.copyOf(map), TldStateTransition.class);
  }
}
