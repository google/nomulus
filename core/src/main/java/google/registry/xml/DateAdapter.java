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
import org.joda.time.LocalDate;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

/**
 * Adapter to use Joda {@link LocalDate} when marshalling the XML Schema {@code date} type.
 *
 * <p>Dates are represented as midnight in UTC. The parser aims to be permissive in what it accepts.
 * Timestamps are converted to UTC if a zone is specified and then the time section is truncated.
 * This can lead to unexpected behavior, but it will be your fault.
 */
public class DateAdapter extends XmlAdapter<String, LocalDate> {

  /** @see ISODateTimeFormat#date */
  private static final DateTimeFormatter MARSHAL_FORMAT = ISODateTimeFormat.date();

  /** @see ISODateTimeFormat#dateTimeParser */
  private static final DateTimeFormatter UNMARSHAL_FORMAT = ISODateTimeFormat.dateTimeParser();

  /**
   * Parses an ISO timestamp string into a UTC {@link LocalDate} object, converting timezones
   * and truncating time to midnight if necessary. If {@code timestamp} is empty or {@code null}
   * then {@code null} is returned.
   */
  @Nullable
  @CheckForNull
  @Override
  public LocalDate unmarshal(@Nullable String timestamp) {
    if (isNullOrEmpty(timestamp)) {
      return null;
    }
    return UNMARSHAL_FORMAT.parseDateTime(timestamp).withZone(UTC).toLocalDate();
  }

  /**
   * Converts {@link LocalDate} to UTC and returns it as an RFC3339 string. If {@code timestamp}
   * is {@code null} then an empty string is returned.
   */
  @Override
  public String marshal(@Nullable LocalDate date) {
    if (date == null) {
      return "";
    }
    return MARSHAL_FORMAT.print(date.toDateTimeAtStartOfDay(UTC));
  }
}
