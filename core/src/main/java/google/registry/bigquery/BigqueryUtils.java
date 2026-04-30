// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

import static java.time.ZoneOffset.UTC;
import static java.time.temporal.ChronoUnit.MICROS;

import com.google.api.services.bigquery.model.JobReference;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.util.concurrent.TimeUnit;

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
   * Instant only supports up to millisecond precision) and the zone if not specified defaults to
   * UTC.
   *
   * <p>Although we expect a zone specification of "UTC" when parsing, we don't emit it when
   * printing because in some cases BigQuery does not allow any time zone specification (instead it
   * assumes UTC for whatever input you provide) for input timestamp strings (see b/16380363).
   *
   * @see <a href="https://cloud.google.com/bigquery/data-types#timestamp-type">BigQuery Data Types
   *     - TIMESTAMP</a>
   */
  private static final DateTimeFormatter BIGQUERY_TIMESTAMP_PARSER =
      new DateTimeFormatterBuilder()
          .appendValue(ChronoField.YEAR, 4, 10, SignStyle.NOT_NEGATIVE)
          .appendLiteral('-')
          .appendValue(ChronoField.MONTH_OF_YEAR, 2)
          .appendLiteral('-')
          .appendValue(ChronoField.DAY_OF_MONTH, 2)
          .appendLiteral(' ')
          .appendPattern("HH:mm:ss")
          .optionalStart()
          .appendFraction(ChronoField.MILLI_OF_SECOND, 0, 3, true)
          .optionalEnd()
          .appendLiteral(" UTC")
          .toFormatter()
          .withZone(UTC);

  private static final DateTimeFormatter BIGQUERY_TIMESTAMP_PRINTER =
      new DateTimeFormatterBuilder()
          .appendValue(ChronoField.YEAR, 4, 10, SignStyle.NOT_NEGATIVE)
          .appendLiteral('-')
          .appendValue(ChronoField.MONTH_OF_YEAR, 2)
          .appendLiteral('-')
          .appendValue(ChronoField.DAY_OF_MONTH, 2)
          .appendLiteral(' ')
          .appendPattern("HH:mm:ss")
          .appendFraction(ChronoField.MILLI_OF_SECOND, 3, 3, true)
          .toFormatter()
          .withZone(UTC);

  /**
   * Returns the human-readable string version of the given Instant, suitable for conversion within
   * BigQuery from a string literal into a BigQuery timestamp type.
   */
  public static String toBigqueryTimestampString(Instant dateTime) {
    return BIGQUERY_TIMESTAMP_PRINTER.format(dateTime);
  }

  /** Returns the Instant for a given human-readable string-formatted BigQuery timestamp. */
  public static Instant fromBigqueryTimestampString(String timestampString) {
    return BIGQUERY_TIMESTAMP_PARSER.parse(timestampString, Instant::from);
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
   * Converts a time into a numeric string that BigQuery understands as a timestamp: the decimal
   * number of seconds since the epoch, precise up to microseconds.
   *
   * <p>Note that while {@code Instant} supports nanosecond precision, BigQuery only supports
   * microsecond precision, so the sub-microsecond precision is truncated.
   *
   * @see <a href="https://developers.google.com/bigquery/timestamp">Data Types</a>
   */
  public static String toBigqueryTimestamp(Instant dateTime) {
    return toBigqueryTimestamp(MICROS.between(Instant.EPOCH, dateTime), TimeUnit.MICROSECONDS);
  }

  /**
   * Returns the canonical string format for a JobReference object (the project ID and then job ID,
   * delimited by a single colon) since JobReference.toString() is not customized to return it.
   */
  public static String toJobReferenceString(JobReference jobRef) {
    return jobRef.getProjectId() + ":" + jobRef.getJobId();
  }
}
