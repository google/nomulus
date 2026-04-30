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

package google.registry.xml;

import static com.google.common.base.Strings.isNullOrEmpty;
import static google.registry.util.DateTimeUtils.parseInstant;
import static java.time.ZoneOffset.UTC;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

/**
 * Adapter to use java.time {@link Instant} when marshalling XML timestamps.
 *
 * <p>These fields shall contain timestamps indicating the date and time in UTC as specified in
 * RFC3339, with no offset from the zero meridian. For example: {@code 2010-10-17T00:00:00Z}.
 */
public class UtcInstantAdapter extends XmlAdapter<String, Instant> {

  private static final DateTimeFormatter MARSHAL_FORMAT =
      DateTimeFormatter.ofPattern("u-MM-dd'T'HH:mm:ss'Z'").withZone(UTC);

  /** Same as {@link #marshal(Instant)}, but in a convenient static format. */
  public static String getFormattedString(@Nullable Instant timestamp) {
    if (timestamp == null) {
      return "";
    }
    return MARSHAL_FORMAT.format(timestamp);
  }

  /**
   * Parses an ISO timestamp string into a UTC {@link Instant} object. If {@code timestamp} is empty
   * or {@code null} then {@code null} is returned.
   */
  @Nullable
  @CheckForNull
  @Override
  public Instant unmarshal(@Nullable String timestamp) {
    if (isNullOrEmpty(timestamp)) {
      return null;
    }
    return parseInstant(timestamp);
  }

  /**
   * Converts {@link Instant} to UTC and returns it as an RFC3339 string. If {@code timestamp} is
   * {@code null} then an empty string is returned.
   */
  @Override
  public String marshal(@Nullable Instant timestamp) {
    return getFormattedString(timestamp);
  }
}
