// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.testing.truth;

import static google.registry.testing.truth.RegistryTruth.assertAt;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RegistryTruth}. */
class RegistryTruthTest {

  private final Instant instant = Instant.parse("2026-04-03T12:00:00Z");
  private final DateTime dateTime = new DateTime("2026-04-03T12:00:00Z", UTC);

  @Test
  void testInstant_isAt_dateTime() {
    assertAt(instant).isAt(dateTime);
  }

  @Test
  void testInstant_isAt_instant() {
    assertAt(instant).isAt(instant);
  }

  @Test
  void testDateTime_isAt_instant() {
    assertAt(dateTime).isAt(instant);
  }

  @Test
  void testDateTime_isAt_dateTime() {
    assertAt(dateTime).isAt(dateTime);
  }

  @Test
  void testInstant_isBefore_dateTime() {
    assertAt(instant.minusMillis(1)).isBefore(dateTime);
  }

  @Test
  void testDateTime_isBefore_instant() {
    assertAt(dateTime.minusMillis(1)).isBefore(instant);
  }

  @Test
  void testInstant_isAtOrBefore_dateTime() {
    assertAt(instant).isAtOrBefore(dateTime);
    assertAt(instant.minusMillis(1)).isAtOrBefore(dateTime);
  }

  @Test
  void testInstant_isAtOrAfter_dateTime() {
    assertAt(instant).isAtOrAfter(dateTime);
    assertAt(instant.plusMillis(1)).isAtOrAfter(dateTime);
  }

  @Test
  void testFailure_instant_isAt_dateTime() {
    assertThrows(AssertionError.class, () -> assertAt(instant.plusMillis(1)).isAt(dateTime));
  }

  @Test
  void testFailure_dateTime_isAt_instant() {
    assertThrows(AssertionError.class, () -> assertAt(dateTime.plusMillis(1)).isAt(instant));
  }
}
