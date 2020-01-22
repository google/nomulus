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

package google.registry.xml;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.joda.time.LocalDate;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link DateAdapter}. */
@RunWith(JUnit4.class)
public class DateAdapterTest {

  @Test
  public void testMarshal() {
    assertThat(new DateAdapter().marshal(
        new LocalDate(2010, 10, 17))).isEqualTo("2010-10-17");
  }

  @Test
  public void testMarshalEmpty() {
    assertThat(new DateAdapter().marshal(null)).isEmpty();
  }

  @Test
  public void testUnmarshal() {
    assertThat(new DateAdapter().unmarshal("2010-10-17T04:20:00Z"))
        .isEqualTo(new LocalDate(2010, 10, 17));
  }

  @Test
  public void testUnmarshalConvertsToZuluTime() {
    assertThat(new DateAdapter().unmarshal("2010-10-17T23:23:23-04:00"))
        .isEqualTo(new LocalDate(2010, 10, 18));
  }

  @Test
  public void testUnmarshalEmpty() {
    assertThat(new DateAdapter().unmarshal(null)).isNull();
    assertThat(new DateAdapter().unmarshal("")).isNull();
  }

  @Test
  public void testUnmarshalInvalid() {
    assertThrows(
        IllegalArgumentException.class,
        () -> assertThat(new DateAdapter().unmarshal("oh my goth")).isNull());
  }
}
