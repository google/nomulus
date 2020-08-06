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
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link IntervalParameter}. */
class IntervalParameterTest {

  private final IntervalParameter instance = new IntervalParameter();

  @Test
  void testConvert() {
    assertThat(instance.convert("2004-06-09T12:30:00Z/2004-07-10T13:30:00Z"))
        .isEqualTo(new Interval(
            DateTime.parse("2004-06-09T12:30:00Z"),
            DateTime.parse("2004-07-10T13:30:00Z")));
  }

  @Test
  void testConvert_singleDate() {
    assertThrows(IllegalArgumentException.class, () -> instance.convert("2004-06-09T12:30:00Z"));
  }

  @Test
  void testConvert_backwardsInterval() {
    assertThrows(
        IllegalArgumentException.class,
        () -> instance.convert("2004-07-10T13:30:00Z/2004-06-09T12:30:00Z"));
  }

  @Test
  void testConvert_empty_throws() {
    assertThrows(IllegalArgumentException.class, () -> instance.convert(""));
  }

  @Test
  void testConvert_null_throws() {
    assertThrows(NullPointerException.class, () -> instance.convert(null));
  }

  @Test
  void testConvert_sillyString_throws() {
    assertThrows(IllegalArgumentException.class, () -> instance.convert("foo"));
  }

  @Test
  void testValidate_sillyString_throws() {
    ParameterException thrown =
        assertThrows(ParameterException.class, () -> instance.validate("--time", "foo"));
    assertThat(thrown).hasMessageThat().contains("--time=foo not an");
  }
}
