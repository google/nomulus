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

package google.registry.persistence;

import static com.google.common.collect.ImmutableList.toImmutableList;

import google.registry.persistence.StringCollectionDescriptor.StringCollection;
import java.util.List;
import javax.persistence.AttributeConverter;

/**
 * Base JPA converter for {@link List} objects that are stored as an array of strings in the
 * database.
 */
public abstract class StringListConverterBase<T>
    implements AttributeConverter<List<T>, StringCollection> {

  abstract String toString(T element);

  abstract T fromString(String value);

  @Override
  public StringCollection convertToDatabaseColumn(List<T> attribute) {
    return attribute == null
        ? null
        : StringCollection.create(
            attribute.stream().map(this::toString).collect(toImmutableList()));
  }

  @Override
  public List<T> convertToEntityAttribute(StringCollection dbData) {
    return dbData == null || dbData.getCollection() == null
        ? null
        : dbData.getCollection().stream().map(this::fromString).collect(toImmutableList());
  }
}
