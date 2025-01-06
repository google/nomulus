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

package google.registry.bigquery;

import com.google.api.services.bigquery.model.JobReference;
import java.util.concurrent.TimeUnit;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.DateTimeFormatterBuilder;
import org.joda.time.format.DateTimeParser;
import org.joda.time.format.ISODateTimeFormat;

/** Utilities related to Bigquery. */
public class BigqueryUtils {

  /** Bigquery schema field types. */
  public enum FieldType {
    STRING,
    INTEGER,
    FLOAT,
    TIMESTAMP,
    RECORD,
    BOOLEAN
  }

  /** Destination formats for Bigquery extract jobs. */
  public enum DestinationFormat {
    CSV,
    NEWLINE_DELIMITED_JSON
  }

  /** Bigquery table types (i.e. regular table or view). */
  public enum TableType {
    TABLE,
    VIEW
  }

  /**
   * Bigquery write dispositions (i.e. what to do about writing to an existing table).
   *
   * @see <a href="https://developers.google.com/bigquery/docs/reference/v2/jobs">API docs</a>
   */
  public enum WriteDisposition {
    /** Only write to the table if there is no existing table or if it is empty. */
    WRITE_EMPTY,
    /** If the table already exists, overwrite it with the new data. */
    WRITE_TRUNCATE,
    /** If the table already exists, append the data to the table. */
    WRITE_APPEND
  }

  /**
   * A {@code DateTimeFormatter} that defines how to print DateTimes in a string format that
   * BigQuery can interpret and how to parse the string formats that BigQuery emits into DateTimes.
   *
   * <p>The general format definition is "YYYY-MM-DD HH:MM:SS.SSS[ ZZ]", where the fractional
   * seconds portion can have 0-6 decimal places (although we restrict it to 0-3 here since Joda
   * DateTime only supports up to millisecond precision) and the zone if not specified defaults to
   * UTC.
   *
   * <p>Although we expect a zone specification of "UTC" when parsing, we don't emit it when
   * printing because in some cases BigQuery does not allow any time zone specification (instead it
   * assumes UTC for whatever input you provide) for input timestamp strings (see b/16380363).
   *
   * @see <a href="https://cloud.google.com/bigquery/data-types#timestamp-type">
   *     BigQuery Data Types - TIMESTAMP</a>
   */
  public static final DateTimeFormatter BIGQUERY_TIMESTAMP_FORMAT = new DateTimeFormatterBuilder()
      .append(ISODateTimeFormat.date())
      .appendLiteral(' ')
      .append(
          // For printing, always print out the milliseconds.
          ISODateTimeFormat.hourMinuteSecondMillis().getPrinter(),
          // For parsing, we need a series of parsers to correctly handle the milliseconds.
          new DateTimeParser[] {
              // Try to parse the time with milliseconds first, which requires at least one
              // fractional second digit, and if that fails try to parse without milliseconds.
              ISODateTimeFormat.hourMinuteSecondMillis().getParser(),
              ISODateTimeFormat.hourMinuteSecond().getParser()})
      // Print UTC as the empty string since BigQuery's TIMESTAMP() function does not accept any
      // time zone specification, but require "UTC" on parsing.  Since we force this formatter to
      // always use UTC below, the other arguments do not matter.  If b/16380363 ever gets resolved
      // this could be simplified to appendLiteral(" UTC").
      .appendTimeZoneOffset("", " UTC", false, 1, 1)
      .toFormatter()
      .withZoneUTC();

  /**
   * Returns the human-readable string version of the given DateTime, suitable for conversion
   * within BigQuery from a string literal into a BigQuery timestamp type.
   */
  public static String toBigqueryTimestampString(DateTime dateTime) {
    return BIGQUERY_TIMESTAMP_FORMAT.print(dateTime);
  }

  /** Returns the DateTime for a given human-readable string-formatted BigQuery timestamp. */
  public static DateTime fromBigqueryTimestampString(String timestampString) {
    return BIGQUERY_TIMESTAMP_FORMAT.parseDateTime(timestampString);
  }

  /**
   * Converts a time (in TimeUnits since the epoch) into a numeric string that BigQuery understands
   * as a timestamp: the decimal number of seconds since the epoch, precise up to microseconds.
   *
   * @see <a href="https://developers.google.com/bigquery/timestamp">Data Types</a>
   */
  public static String toBigqueryTimestamp(long timestamp, TimeUnit unit) {
    long seconds = unit.toSeconds(timestamp);
    long fractionalSeconds = unit.toMicros(timestamp) % 1000000;
    return String.format("%d.%06d", seconds, fractionalSeconds);
  }

  /**
   * Converts a {@link DateTime} into a numeric string that BigQuery understands as a timestamp:
   * the decimal number of seconds since the epoch, precise up to microseconds.
   *
   * <p>Note that since {@code DateTime} only stores milliseconds, the last 3 digits will be zero.
   *
   * @see <a href="https://developers.google.com/bigquery/timestamp">Data Types</a>
   */
  public static String toBigqueryTimestamp(DateTime dateTime) {
    return toBigqueryTimestamp(dateTime.getMillis(), TimeUnit.MILLISECONDS);
  }

  /**
   * Returns the canonical string format for a JobReference object (the project ID and then job ID,
   * delimited by a single colon) since JobReference.toString() is not customized to return it.
   */
  public static String toJobReferenceString(JobReference jobRef) {
    return jobRef.getProjectId() + ":" + jobRef.getJobId();
  }
}
