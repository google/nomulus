// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.schema.tld;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.registry.label.ReservationType.FULLY_BLOCKED;
import static google.registry.testing.JUnitBackports.assertThrows;

import com.google.common.collect.ImmutableMap;
import google.registry.model.registry.label.ReservationType;
import google.registry.schema.tld.ReservedList.ReservedEntry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ReservedList} */
@RunWith(JUnit4.class)
public class ReservedListTest {

  @Test
  public void verifyConstructorAndGetters_workCorrectly() {
    ReservedList reservedList =
        ReservedList.create(
            "app",
            false,
            ImmutableMap.of(
                "book",
                ReservedEntry.create(ReservationType.ALLOWED_IN_SUNRISE, null),
                "music",
                ReservedEntry.create(
                    ReservationType.RESERVED_FOR_ANCHOR_TENANT, "reserved for anchor tenant")));

    assertThat(reservedList.getName()).isEqualTo("app");
    assertThat(reservedList.getShouldPublish()).isFalse();
    assertThat(reservedList.getLabelsToReservations())
        .containsExactly(
            "book",
            ReservedEntry.create(ReservationType.ALLOWED_IN_SUNRISE, null),
            "music",
            ReservedEntry.create(
                ReservationType.RESERVED_FOR_ANCHOR_TENANT, "reserved for anchor tenant"));
  }

  @Test
  public void create_throwsExceptionWhenLabelIsNotLowercase() {
    Exception e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ReservedList.create(
                    "UPPER.tld",
                    true,
                    ImmutableMap.of("UPPER", ReservedEntry.create(FULLY_BLOCKED, ""))));
    assertThat(e)
        .hasMessageThat()
        .contains("Label(s) [UPPER] must be in puny-coded, lower-case form");
  }

  @Test
  public void create_labelMustBePunyCoded() {
    Exception e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ReservedList.create(
                    "lower.みんな",
                    true,
                    ImmutableMap.of("みんな", ReservedEntry.create(FULLY_BLOCKED, ""))));
    assertThat(e)
        .hasMessageThat()
        .contains("Label(s) [みんな] must be in puny-coded, lower-case form");
  }
}
