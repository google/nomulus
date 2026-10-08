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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link UtcInstantAdapter}. */
class UtcInstantAdapterTest {

  @Test
  void testMarshal() {
    assertThat(new UtcInstantAdapter().marshal(Instant.parse("2010-10-17T04:20:00Z")))
        .isEqualTo("2010-10-17T04:20:00.000Z");
  }

  @Test
  void testMarshalConvertsToZuluTime() {
    assertThat(new UtcInstantAdapter().marshal(Instant.parse("2010-10-17T00:20:00-04:00")))
        .isEqualTo("2010-10-17T04:20:00.000Z");
  }

  @Test
  void testMarshalEmpty() {
    assertThat(new UtcInstantAdapter().marshal(null)).isEmpty();
  }

  @Test
  void testUnmarshal() {
    assertThat(new UtcInstantAdapter().unmarshal("2010-10-17T04:20:00Z"))
        .isEqualTo(Instant.parse("2010-10-17T04:20:00Z"));
  }

  @Test
  void testUnmarshalConvertsToZuluTime() {
    assertThat(new UtcInstantAdapter().unmarshal("2010-10-17T00:20:00-04:00"))
        .isEqualTo(Instant.parse("2010-10-17T04:20:00Z"));
  }

  @Test
  void testUnmarshalEmpty() {
    assertThat(new UtcInstantAdapter().unmarshal(null)).isNull();
    assertThat(new UtcInstantAdapter().unmarshal("")).isNull();
  }

  @Test
  void testUnmarshalInvalid() {
    assertThrows(
        DateTimeParseException.class,
        () -> assertThat(new UtcInstantAdapter().unmarshal("oh my goth")).isNull());
  }
}
