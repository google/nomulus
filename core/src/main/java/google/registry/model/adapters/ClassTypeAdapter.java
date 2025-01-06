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

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;

/**
 * TypeAdapter for {@link Class} objects.
 *
 * <p>GSON's default adapter doesn't allow this, but we want to allow for (de)serialization of Class
 * objects for containers like VKeys using the full name of the class.
 */
public class ClassTypeAdapter extends TypeAdapter<Class<?>> {

  @Override
  public void write(JsonWriter out, Class value) throws IOException {
    out.value(value.getName());
  }

  @Override
  public Class<?> read(JsonReader reader) throws IOException {
    String stringValue = reader.nextString();
    if (stringValue.equals("null")) {
      return null;
    }
    try {
      return Class.forName(stringValue);
    } catch (ClassNotFoundException e) {
      // this should not happen...
      throw new RuntimeException(e);
    }
  }
}
