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

package google.registry.xml;

import static com.google.common.base.Strings.isNullOrEmpty;
import static org.joda.time.DateTimeZone.UTC;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

/**
 * Adapter to use Joda {@link DateTime} when marshalling XML timestamps.
 *
 * <p>These fields shall contain timestamps indicating the date and time in UTC as specified in
 * RFC3339, with no offset from the zero meridian. For example: {@code 2010-10-17T00:00:00Z}.
 */
public class UtcDateTimeAdapter extends XmlAdapter<String, DateTime> {

  /** @see ISODateTimeFormat#dateTimeNoMillis */
  private static final DateTimeFormatter MARSHAL_FORMAT = ISODateTimeFormat.dateTimeNoMillis();

  /** @see ISODateTimeFormat#dateTimeParser */
  private static final DateTimeFormatter UNMARSHAL_FORMAT = ISODateTimeFormat.dateTimeParser();

  /** Same as {@link #marshal(DateTime)}, but in a convenient static format. */
  public static String getFormattedString(@Nullable DateTime timestamp) {
    if (timestamp == null) {
      return "";
    }
    return MARSHAL_FORMAT.print(timestamp.toDateTime(UTC));
  }

  /**
   * Parses an ISO timestamp string into a UTC {@link DateTime} object, converting timezones if
   * necessary. If {@code timestamp} is empty or {@code null} then {@code null} is returned.
   */
  @Nullable
  @CheckForNull
  @Override
  public DateTime unmarshal(@Nullable String timestamp) {
    if (isNullOrEmpty(timestamp)) {
      return null;
    }
    return UNMARSHAL_FORMAT.parseDateTime(timestamp).withZone(UTC);
  }

  /**
   * Converts {@link DateTime} to UTC and returns it as an RFC3339 string. If {@code timestamp} is
   * {@code null} then an empty string is returned.
   */
  @Override
  public String marshal(@Nullable DateTime timestamp) {
    return getFormattedString(timestamp);
  }
}
