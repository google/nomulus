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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.Collection;
import javax.annotation.Nullable;
import javax.persistence.AttributeConverter;

/** Abstract JPA converter for converting {@link Collection<String>} to JSON string. */
public abstract class StringCollectionToJsonConverter<T extends Collection<String>>
    implements AttributeConverter<T, String> {

  @Override
  @Nullable
  public String convertToDatabaseColumn(@Nullable T attribute) {
    return attribute == null ? null : new Gson().toJson(attribute);
  }

  @Override
  @Nullable
  public T convertToEntityAttribute(@Nullable String dbData) {
    if (dbData == null) {
      return null;
    }
    JsonArray array = new Gson().fromJson(dbData, JsonArray.class);
    T collection = getEntityAttributeCollection();
    for (JsonElement e : array) {
      collection.add(e.getAsString());
    }
    return collection;
  }

  /** Returns a collection object to store the entity attribute converted from the column. */
  public abstract T getEntityAttributeCollection();
}
