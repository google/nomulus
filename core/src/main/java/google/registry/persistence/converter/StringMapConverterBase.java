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

import google.registry.persistence.converter.StringMapDescriptor.StringMap;
import java.util.Map;
import javax.persistence.AttributeConverter;

/**
 * Base JPA converter for {@link Map} objects that are stored in a column with data type of hstore
 * in the database.
 */
public abstract class StringMapConverterBase<K, V>
    implements AttributeConverter<Map<K, V>, StringMap> {

  abstract Map.Entry<String, String> convertToDatabaseMapEntry(Map.Entry<K, V> entry);

  abstract Map.Entry<K, V> convertToEntityMapEntry(Map.Entry<String, String> entry);

  @Override
  public StringMap convertToDatabaseColumn(Map<K, V> attribute) {
    return attribute == null
        ? null
        : StringMap.create(
            attribute.entrySet().stream()
                .map(this::convertToDatabaseMapEntry)
                .collect(entriesToImmutableMap()));
  }

  @Override
  public Map<K, V> convertToEntityAttribute(StringMap dbData) {
    return dbData == null
        ? null
        : dbData.getMap().entrySet().stream()
            .map(this::convertToEntityMapEntry)
            .collect(entriesToImmutableMap());
  }
}
