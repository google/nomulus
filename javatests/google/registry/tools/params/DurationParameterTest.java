// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

import com.beust.jcommander.ParameterException;
import google.registry.testing.ExceptionRule;
import org.joda.time.Duration;
import org.joda.time.Period;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link DurationParameter}. */
@RunWith(JUnit4.class)
public class DurationParameterTest {

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  private final DurationParameter instance = new DurationParameter();

  @Test
  public void testConvert_isoHours() throws Exception {
    assertThat(instance.convert("PT36H")).isEqualTo(Duration.standardHours(36));
  }

  @Test
  public void testConvert_isoDaysAndHours() throws Exception {
    assertThat(instance.convert("P1DT12H")).isEqualTo(Duration.standardHours(36));
  }

  @Test
  public void testConvert_isoLowercase_isAllowed() throws Exception {
    assertThat(instance.convert("pt36h")).isEqualTo(Duration.standardHours(36));
  }

  @Test
  public void testIsoMissingP_notAllowed() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    Period.parse("T36H");
  }

  @Test
  public void testIsoMissingPT_notAllowed() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    Period.parse("36H");
  }

  @Test
  public void testConvert_isoMissingP_notAllowed() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    instance.convert("T36H");
  }

  @Test
  public void testConvert_null_throws() throws Exception {
    thrown.expect(NullPointerException.class);
    instance.convert(null);
  }

  @Test
  public void testConvert_empty_throws() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    instance.convert("");
  }

  @Test
  public void testConvert_numeric_throws() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    instance.convert("1234");
  }

  @Test
  public void testConvert_sillyString_throws() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    instance.convert("foo");
  }

  @Test
  public void testValidate_sillyString_throws() throws Exception {
    thrown.expect(ParameterException.class, "--time=foo not an");
    instance.validate("--time", "foo");
  }
}
