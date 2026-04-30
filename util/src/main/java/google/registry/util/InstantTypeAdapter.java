// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.util.DateTimeUtils.formatInstant;
import static google.registry.util.DateTimeUtils.parseInstant;

import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.time.Instant;

/** GSON type adapter for {@link Instant} objects. */
public class InstantTypeAdapter extends StringBaseTypeAdapter<Instant> {

  /** Parses an ISO-8601 string to an {@link Instant}. */
  @Override
  protected Instant fromString(String stringValue) throws IOException {
    try {
      return parseInstant(stringValue);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  /**
   * Writes the {@link Instant} to the given {@link JsonWriter} as a millisecond-precision string.
   */
  @Override
  public void write(JsonWriter writer, Instant value) throws IOException {
    if (value == null) {
      writer.value("null");
    } else {
      writer.value(formatInstant(value));
    }
  }
}
