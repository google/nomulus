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

package google.registry.model.adapters;

import google.registry.util.StringBaseTypeAdapter;
import java.io.IOException;
import java.io.Serializable;

/**
 * TypeAdapter for {@link Serializable} objects.
 *
 * <p>VKey keys (primary keys in SQL) are usually represented by either a long or a String. There
 * are a couple situations (CursorId, HistoryEntryId) where the Serializable in question is a
 * complex object, but we do not need to worry about (de)serializing those objects to/from JSON.
 */
public class SerializableJsonTypeAdapter extends StringBaseTypeAdapter<Serializable> {

  @Override
  protected Serializable fromString(String stringValue) throws IOException {
    try {
      return Long.parseLong(stringValue);
    } catch (NumberFormatException e) {
      return stringValue;
    }
  }
}
