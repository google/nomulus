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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.bigquery.BigqueryUtils.fromBigqueryTimestampString;
import static google.registry.bigquery.BigqueryUtils.toBigqueryTimestamp;
import static google.registry.bigquery.BigqueryUtils.toBigqueryTimestampString;
import static google.registry.bigquery.BigqueryUtils.toJobReferenceString;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.Assert.assertThrows;

import com.google.api.services.bigquery.model.JobReference;
import java.util.concurrent.TimeUnit;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link BigqueryUtils}. */
@RunWith(JUnit4.class)
public class BigqueryUtilsTest {
  private static final DateTime DATE_0 = DateTime.parse("2014-07-17T20:35:42Z");
  private static final DateTime DATE_1 = DateTime.parse("2014-07-17T20:35:42.1Z");
  private static final DateTime DATE_2 = DateTime.parse("2014-07-17T20:35:42.12Z");
  private static final DateTime DATE_3 = DateTime.parse("2014-07-17T20:35:42.123Z");

  @Test
  public void test_toBigqueryTimestampString() {
    assertThat(toBigqueryTimestampString(START_OF_TIME)).isEqualTo("1970-01-01 00:00:00.000");
    assertThat(toBigqueryTimestampString(DATE_0)).isEqualTo("2014-07-17 20:35:42.000");
    assertThat(toBigqueryTimestampString(DATE_1)).isEqualTo("2014-07-17 20:35:42.100");
    assertThat(toBigqueryTimestampString(DATE_2)).isEqualTo("2014-07-17 20:35:42.120");
    assertThat(toBigqueryTimestampString(DATE_3)).isEqualTo("2014-07-17 20:35:42.123");
    assertThat(toBigqueryTimestampString(END_OF_TIME)).isEqualTo("294247-01-10 04:00:54.775");
  }

  @Test
  public void test_toBigqueryTimestampString_convertsToUtc() {
    assertThat(toBigqueryTimestampString(START_OF_TIME.withZone(DateTimeZone.forOffsetHours(5))))
        .isEqualTo("1970-01-01 00:00:00.000");
    assertThat(toBigqueryTimestampString(DateTime.parse("1970-01-01T00:00:00-0500")))
        .isEqualTo("1970-01-01 05:00:00.000");
  }

  @Test
  public void test_fromBigqueryTimestampString_startAndEndOfTime() {
    assertThat(fromBigqueryTimestampString("1970-01-01 00:00:00 UTC")).isEqualTo(START_OF_TIME);
    assertThat(fromBigqueryTimestampString("294247-01-10 04:00:54.775 UTC")).isEqualTo(END_OF_TIME);
  }

  @Test
  public void test_fromBigqueryTimestampString_trailingZerosOkay() {
    assertThat(fromBigqueryTimestampString("2014-07-17 20:35:42 UTC")).isEqualTo(DATE_0);
    assertThat(fromBigqueryTimestampString("2014-07-17 20:35:42.0 UTC")).isEqualTo(DATE_0);
    assertThat(fromBigqueryTimestampString("2014-07-17 20:35:42.00 UTC")).isEqualTo(DATE_0);
    assertThat(fromBigqueryTimestampString("2014-07-17 20:35:42.000 UTC")).isEqualTo(DATE_0);
    assertThat(fromBigqueryTimestampString("2014-07-17 20:35:42.1 UTC")).isEqualTo(DATE_1);
    assertThat(fromBigqueryTimestampString("2014-07-17 20:35:42.10 UTC")).isEqualTo(DATE_1);
    assertThat(fromBigqueryTimestampString("2014-07-17 20:35:42.100 UTC")).isEqualTo(DATE_1);
    assertThat(fromBigqueryTimestampString("2014-07-17 20:35:42.12 UTC")).isEqualTo(DATE_2);
    assertThat(fromBigqueryTimestampString("2014-07-17 20:35:42.120 UTC")).isEqualTo(DATE_2);
    assertThat(fromBigqueryTimestampString("2014-07-17 20:35:42.123 UTC")).isEqualTo(DATE_3);
  }

  @Test
  public void testFailure_fromBigqueryTimestampString_nonUtcTimeZone() {
    assertThrows(
        IllegalArgumentException.class,
        () -> fromBigqueryTimestampString("2014-01-01 01:01:01 +05:00"));
  }

  @Test
  public void testFailure_fromBigqueryTimestampString_noTimeZone() {
    assertThrows(
        IllegalArgumentException.class, () -> fromBigqueryTimestampString("2014-01-01 01:01:01"));
  }

  @Test
  public void testFailure_fromBigqueryTimestampString_tooManyMillisecondDigits() {
    assertThrows(
        IllegalArgumentException.class,
        () -> fromBigqueryTimestampString("2014-01-01 01:01:01.1234 UTC"));
  }

  @Test
  public void test_toBigqueryTimestamp_timeunitConversion() {
    assertThat(toBigqueryTimestamp(1234567890L, TimeUnit.SECONDS))
        .isEqualTo("1234567890.000000");
    assertThat(toBigqueryTimestamp(1234567890123L, TimeUnit.MILLISECONDS))
        .isEqualTo("1234567890.123000");
    assertThat(toBigqueryTimestamp(1234567890123000L, TimeUnit.MICROSECONDS))
        .isEqualTo("1234567890.123000");
    assertThat(toBigqueryTimestamp(1234567890123000000L, TimeUnit.NANOSECONDS))
        .isEqualTo("1234567890.123000");
  }

  @Test
  public void test_toBigqueryTimestamp_timeunitConversionForZero() {
    assertThat(toBigqueryTimestamp(0L, TimeUnit.SECONDS)).isEqualTo("0.000000");
    assertThat(toBigqueryTimestamp(0L, TimeUnit.MILLISECONDS)).isEqualTo("0.000000");
    assertThat(toBigqueryTimestamp(0L, TimeUnit.MICROSECONDS)).isEqualTo("0.000000");
  }

  @Test
  public void test_toBigqueryTimestamp_datetimeConversion() {
    assertThat(toBigqueryTimestamp(START_OF_TIME)).isEqualTo("0.000000");
    assertThat(toBigqueryTimestamp(DATE_0)).isEqualTo("1405629342.000000");
    assertThat(toBigqueryTimestamp(DATE_1)).isEqualTo("1405629342.100000");
    assertThat(toBigqueryTimestamp(DATE_2)).isEqualTo("1405629342.120000");
    assertThat(toBigqueryTimestamp(DATE_3)).isEqualTo("1405629342.123000");
    assertThat(toBigqueryTimestamp(END_OF_TIME)).isEqualTo("9223372036854.775000");
  }

  @Test
  public void test_toJobReferenceString_normalSucceeds() {
    assertThat(toJobReferenceString(new JobReference().setProjectId("foo").setJobId("bar")))
        .isEqualTo("foo:bar");
  }

  @Test
  public void test_toJobReferenceString_emptyReferenceSucceeds() {
    assertThat(toJobReferenceString(new JobReference())).isEqualTo("null:null");
  }

  @Test
  public void test_toJobReferenceString_nullThrowsNpe() {
    assertThrows(NullPointerException.class, () -> toJobReferenceString(null));
  }
}
