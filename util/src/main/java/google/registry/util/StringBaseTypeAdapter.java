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

package google.registry.util;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.Objects;

/** Abstract class for {@link TypeAdapter}s that can convert directly to/from strings. */
public abstract class StringBaseTypeAdapter<T> extends TypeAdapter<T> {

  @Override
  public T read(JsonReader reader) throws IOException {
    String stringValue = reader.nextString();
    if (stringValue.equals("null")) {
      return null;
    }
    return fromString(stringValue);
  }

  @Override
  public void write(JsonWriter writer, T t) throws IOException {
    writer.value(Objects.toString(t));
  }

  protected abstract T fromString(String stringValue) throws IOException;
}
