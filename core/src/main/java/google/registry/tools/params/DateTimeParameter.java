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

package google.registry.tools.params;

import static org.joda.time.DateTimeZone.UTC;

import com.google.common.primitives.Longs;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.DateTimeFormatterBuilder;
import org.joda.time.format.DateTimeParser;
import org.joda.time.format.ISODateTimeFormat;

/** {@linkplain DateTime} CLI parameter converter/validator. Can be ISO or millis from epoch. */
public final class DateTimeParameter extends ParameterConverterValidator<DateTime> {

  public DateTimeParameter() {
    super("not an ISO-8601 timestamp (or millis from epoch)");
  }

  /**
   * Parser for DateTimes that permits only a restricted subset of ISO 8601 datetime syntax.
   * The supported format is "YYYY-MM-DD'T'HH:MM:SS[.SSS]ZZ", i.e. there must be a complete date
   * and at least hours, minutes, seconds, and time zone; milliseconds are optional.
   *
   * <p>We use this instead of the default {@link ISODateTimeFormat#dateTimeParser()} because that
   * parser is very flexible and accepts date times with missing dates, missing dates, and various
   * other unspecified fields that can lead to confusion and ambiguity.
   */
  private static final DateTimeFormatter STRICT_DATE_TIME_PARSER = new DateTimeFormatterBuilder()
      .append(null, new DateTimeParser[] {
          // The formatter will try the following parsers in order until one succeeds.
          ISODateTimeFormat.dateTime().getParser(),
          ISODateTimeFormat.dateTimeNoMillis().getParser()})
      .toFormatter();

  @Override
  public DateTime convert(String value) {
    Long millis = Longs.tryParse(value);
    if (millis != null) {
      return new DateTime(millis.longValue(), UTC);
    }
    return DateTime.parse(value, STRICT_DATE_TIME_PARSER).withZone(UTC);
  }
}
