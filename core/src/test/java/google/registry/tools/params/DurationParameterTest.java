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
import org.joda.time.Duration;
import org.joda.time.Period;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DurationParameter}. */
class DurationParameterTest {

  private final DurationParameter instance = new DurationParameter();

  @Test
  void testConvert_isoHours() {
    assertThat(instance.convert("PT36H")).isEqualTo(Duration.standardHours(36));
  }

  @Test
  void testConvert_isoDaysAndHours() {
    assertThat(instance.convert("P1DT12H")).isEqualTo(Duration.standardHours(36));
  }

  @Test
  void testConvert_isoLowercase_isAllowed() {
    assertThat(instance.convert("pt36h")).isEqualTo(Duration.standardHours(36));
  }

  @Test
  void testIsoMissingP_notAllowed() {
    assertThrows(IllegalArgumentException.class, () -> Period.parse("T36H"));
  }

  @Test
  void testIsoMissingPT_notAllowed() {
    assertThrows(IllegalArgumentException.class, () -> Period.parse("36H"));
  }

  @Test
  void testConvert_isoMissingP_notAllowed() {
    assertThrows(IllegalArgumentException.class, () -> instance.convert("T36H"));
  }

  @Test
  void testConvert_null_throws() {
    assertThrows(NullPointerException.class, () -> instance.convert(null));
  }

  @Test
  void testConvert_empty_throws() {
    assertThrows(IllegalArgumentException.class, () -> instance.convert(""));
  }

  @Test
  void testConvert_numeric_throws() {
    assertThrows(IllegalArgumentException.class, () -> instance.convert("1234"));
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
