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

package google.registry.tools.params;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link InstantParameter}. */
class InstantParameterTest {

  private final InstantParameter instance = new InstantParameter();

  @Test
  void testConvert_numeric_returnsMillisFromEpochUtc() {
    assertThat(instance.convert("1234")).isEqualTo(Instant.ofEpochMilli(1234L));
  }

  @Test
  void testConvert_iso8601_returnsSameAsDateTimeParse() {
    String exampleDate = "2014-01-01T01:02:03.004Z";
    assertThat(instance.convert(exampleDate)).isEqualTo(Instant.parse(exampleDate));
  }

  @Test
  void testConvert_isoDateTimeWithMillis_returnsSameAsDateTimeParse() {
    String exampleDate = "2014-01-01T01:02:03.004Z";
    assertThat(instance.convert(exampleDate)).isEqualTo(Instant.parse(exampleDate));
  }

  @Test
  void testConvert_weirdTimezone_convertsToUtc() {
    assertThat(instance.convert("1984-12-18T00:00:00-05:20"))
        .isEqualTo(Instant.parse("1984-12-18T05:20:00Z"));
  }

  @Test
  void testConvert_null_throwsException() {
    assertThrows(NullPointerException.class, () -> instance.convert(null));
  }

  @Test
  void testConvert_empty_throwsException() {
    assertThrows(DateTimeParseException.class, () -> instance.convert(""));
  }

  @Test
  void testConvert_sillyString_throwsException() {
    assertThrows(DateTimeParseException.class, () -> instance.convert("foo"));
  }

  @Test
  void testConvert_partialDate_throwsException() {
    assertThrows(DateTimeParseException.class, () -> instance.convert("2014-01"));
  }

  @Test
  void testConvert_onlyDate_throwsException() {
    assertThrows(DateTimeParseException.class, () -> instance.convert("2014-01-01"));
  }

  @Test
  void testConvert_partialTime_throwsException() {
    assertThrows(DateTimeParseException.class, () -> instance.convert("T01:02"));
  }

  @Test
  void testConvert_onlyTime_throwsException() {
    assertThrows(DateTimeParseException.class, () -> instance.convert("T01:02:03"));
  }

  @Test
  void testConvert_partialDateAndPartialTime_throwsException() {
    assertThrows(DateTimeParseException.class, () -> instance.convert("9T9"));
  }

  @Test
  void testConvert_dateAndPartialTime_throwsException() {
    assertThrows(DateTimeParseException.class, () -> instance.convert("2014-01-01T01:02"));
  }

  @Test
  void testConvert_noTimeZone_throwsException() {
    assertThrows(DateTimeParseException.class, () -> instance.convert("2014-01-01T01:02:03"));
  }

  @Test
  void testValidate_sillyString_throwsParameterException() {
    ParameterException thrown =
        assertThrows(ParameterException.class, () -> instance.validate("--time", "foo"));
    assertThat(thrown).hasMessageThat().contains("--time=foo not an ISO");
  }

  @Test
  void testValidate_correctInput_doesntThrow() {
    instance.validate("--time", "123");
  }
}
