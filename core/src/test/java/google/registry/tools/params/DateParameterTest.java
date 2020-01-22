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
import static org.junit.Assert.assertThrows;

import org.joda.time.DateTime;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link DateParameter}. */
@RunWith(JUnit4.class)
public class DateParameterTest {

  private final DateParameter instance = new DateParameter();

  @Test
  public void testConvert_onlyDate() {
    String exampleDate = "2014-01-01";
    assertThat(instance.convert(exampleDate)).isEqualTo(DateTime.parse("2014-01-01T00:00:00Z"));
  }

  @Test
  public void testConvert_numeric_throwsException() {
    assertThrows(IllegalArgumentException.class, () -> instance.convert("1234"));
  }

  @Test
  public void testConvert_validDateAndTime_throwsException() {
    assertThrows(
        IllegalArgumentException.class, () -> instance.convert("2014-01-01T01:02:03.004Z"));
  }

  @Test
  public void testConvert_invalidDate_throwsException() {
    assertThrows(IllegalArgumentException.class, () -> instance.convert("2014-13-33"));
  }

  @Test
  public void testConvert_null_throwsException() {
    assertThrows(NullPointerException.class, () -> instance.convert(null));
  }

  @Test
  public void testConvert_empty_throwsException() {
    assertThrows(IllegalArgumentException.class, () -> instance.convert(""));
  }

  @Test
  public void testConvert_sillyString_throwsException() {
    assertThrows(IllegalArgumentException.class, () -> instance.convert("foo"));
  }

  @Test
  public void testConvert_partialDate_throwsException() {
    assertThrows(IllegalArgumentException.class, () -> instance.convert("2014-01"));
  }

  @Test
  public void testConvert_onlyTime_throwsException() {
    assertThrows(IllegalArgumentException.class, () -> instance.convert("T01:02:03"));
  }

  @Test
  public void testConvert_partialDateAndPartialTime_throwsException() {
    assertThrows(IllegalArgumentException.class, () -> instance.convert("9T9"));
  }

  @Test
  public void testConvert_dateAndPartialTime_throwsException() {
    assertThrows(IllegalArgumentException.class, () -> instance.convert("2014-01-01T01:02"));
  }
}
