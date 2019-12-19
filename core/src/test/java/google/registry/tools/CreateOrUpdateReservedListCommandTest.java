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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.registry.label.ReservationType.ALLOWED_IN_SUNRISE;
import static google.registry.model.registry.label.ReservationType.FULLY_BLOCKED;
import static google.registry.testing.JUnitBackports.assertThrows;
import static google.registry.tools.CreateOrUpdateReservedListCommand.parseToReservationsByLabels;

import com.google.common.collect.ImmutableList;
import google.registry.model.registry.label.ReservationType;
import google.registry.schema.tld.ReservedList.ReservedEntry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CreateOrUpdateReservedListCommand}. */
@RunWith(JUnit4.class)
public class CreateOrUpdateReservedListCommandTest {

  @Test
  public void parseToReservationsByLabels_worksCorrectly() {
    assertThat(
            parseToReservationsByLabels(
                ImmutableList.of(
                    "reserveddomain,FULLY_BLOCKED",
                    "availableinga,ALLOWED_IN_SUNRISE#allowed_in_sunrise",
                    "fourletterword,FULLY_BLOCKED")))
        .containsExactly(
            "reserveddomain",
            ReservedEntry.create(ReservationType.FULLY_BLOCKED, ""),
            "availableinga",
            ReservedEntry.create(ALLOWED_IN_SUNRISE, "allowed_in_sunrise"),
            "fourletterword",
            ReservedEntry.create(FULLY_BLOCKED, ""));
  }

  @Test
  public void parseToReservationsByLabels_throwsExceptionForInvalidLabel() {
    Throwable thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                parseToReservationsByLabels(
                    ImmutableList.of("reserveddomain,FULLY_BLOCKED", "UPPER,FULLY_BLOCKED")));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Label 'UPPER' must be in puny-coded, lower-case form");
  }

  @Test
  public void parseToReservationsByLabels_throwsExceptionForNonPunyCodedLabel() {
    Throwable thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                parseToReservationsByLabels(
                    ImmutableList.of("reserveddomain,FULLY_BLOCKED", "みんな,FULLY_BLOCKED")));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Label 'みんな' must be in puny-coded, lower-case form");
  }

  @Test
  public void parseToReservationsByLabels_throwsExceptionForInvalidReservationType() {
    Throwable thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                parseToReservationsByLabels(
                    ImmutableList.of(
                        "reserveddomain,FULLY_BLOCKED", "invalidtype,INVALID_RESERVATION_TYPE")));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "No enum constant"
                + " google.registry.model.registry.label.ReservationType.INVALID_RESERVATION_TYPE");
  }

  @Test
  public void parseToReservationsByLabels_throwsExceptionForInvalidLines() {
    Throwable thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                parseToReservationsByLabels(
                    ImmutableList.of(
                        "reserveddomain,FULLY_BLOCKED,too,many,parts",
                        "fourletterword,FULLY_BLOCKED")));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Could not parse line in reserved list: reserveddomain,FULLY_BLOCKED,too,many,parts");
  }

  @Test
  public void parseToReservationsByLabels_throwsExceptionForDuplicateEntries() {
    Throwable thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                parseToReservationsByLabels(
                    ImmutableList.of(
                        "reserveddomain,FULLY_BLOCKED",
                        "fourletterword,FULLY_BLOCKED",
                        "fourletterword,FULLY_BLOCKED")));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Reserved list cannot contain duplicate labels. Dupes (with counts) were:"
                + " [fourletterword x 2]");
  }
}
