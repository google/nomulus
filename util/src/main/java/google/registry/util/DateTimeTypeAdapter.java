// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;

/** GSON type adapter for Joda {@link DateTime} objects. */
public class DateTimeTypeAdapter extends TypeAdapter<DateTime> {

  @Override
  public void write(JsonWriter out, DateTime value) throws IOException {
    out.value(Objects.toString(value));
  }

  @Override
  public DateTime read(JsonReader in) throws IOException {
    String stringValue = in.nextString();
    if (stringValue.equals("null")) {
      return null;
    }
    return ISODateTimeFormat.dateTime().withZoneUTC().parseDateTime(stringValue);
  }
}
