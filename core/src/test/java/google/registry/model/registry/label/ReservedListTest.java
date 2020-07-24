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

package google.registry.model.registry.label;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.monitoring.metrics.contrib.DistributionMetricSubject.assertThat;
import static com.google.monitoring.metrics.contrib.LongMetricSubject.assertThat;
import static google.registry.model.registry.label.DomainLabelMetrics.reservedListChecks;
import static google.registry.model.registry.label.DomainLabelMetrics.reservedListHits;
import static google.registry.model.registry.label.DomainLabelMetrics.reservedListProcessingTime;
import static google.registry.model.registry.label.ReservationType.ALLOWED_IN_SUNRISE;
import static google.registry.model.registry.label.ReservationType.FULLY_BLOCKED;
import static google.registry.model.registry.label.ReservationType.NAME_COLLISION;
import static google.registry.model.registry.label.ReservedList.getReservationTypes;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistReservedList;
import static google.registry.testing.DatastoreHelper.persistResource;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.ofy.Ofy;
import google.registry.model.registry.Registry;
import google.registry.model.registry.label.ReservedList.ReservedListEntry;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ReservedList}. */
class ReservedListTest {

  @RegisterExtension final InjectRule inject = new InjectRule();

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  private FakeClock clock = new FakeClock(DateTime.parse("2010-01-01T10:00:00Z"));

  @BeforeEach
  void beforeEach() {
    inject.setStaticField(Ofy.class, "clock", clock);
    createTld("tld");
    reservedListChecks.reset();
    reservedListProcessingTime.reset();
    reservedListHits.reset();
  }

  private static void verifyUnreservedCheckCount(int unreservedCount) {
    assertThat(reservedListChecks)
        .hasValueForLabels(unreservedCount, "tld", "0", "(none)", "(none)")
        .and()
        .hasNoOtherValues();
    assertThat(reservedListProcessingTime)
        .hasAnyValueForLabels("tld", "0", "(none)", "(none)")
        .and()
        .hasNoOtherValues();
    assertThat(reservedListHits).hasNoOtherValues();
  }

  @Test
  void testGetReservationTypes_allLabelsAreUnreserved_withNoReservedLists() {
    assertThat(Registry.get("tld").getReservedLists()).isEmpty();
    assertThat(getReservationTypes("doodle", "tld")).isEmpty();
    assertThat(getReservationTypes("access", "tld")).isEmpty();
    assertThat(getReservationTypes("rich", "tld")).isEmpty();
    verifyUnreservedCheckCount(3);
  }

  @Test
  void testZeroReservedLists_doesNotCauseError() {
    assertThat(getReservationTypes("doodle", "tld")).isEmpty();
    verifyUnreservedCheckCount(1);
  }

  @Test
  void testGetReservationTypes_twoLetterCodesAreAvailable() {
    for (String sld : ImmutableList.of("aa", "az", "zz", "91", "1n", "j5")) {
      assertThat(getReservationTypes(sld, "tld")).isEmpty();
    }
    verifyUnreservedCheckCount(6);
  }

  @Test
  void testGetReservationTypes_singleCharacterDomainsAreAllowed() {
    // This isn't quite exhaustive but it's close.
    for (char c = 'a'; c <= 'z'; c++) {
      assertThat(getReservationTypes("" + c, "tld")).isEmpty();
    }
    verifyUnreservedCheckCount(26);
  }

  @Test
  void testGetReservationTypes_concatsMultipleListsCorrectly() {
    ReservedList rl1 = persistReservedList(
        "reserved1",
        "lol,FULLY_BLOCKED # yup",
        "cat,FULLY_BLOCKED");
    ReservedList rl2 = persistReservedList(
        "reserved2",
        "roflcopter,FULLY_BLOCKED",
        "snowcrash,FULLY_BLOCKED");
    createTld("tld");
    persistResource(Registry.get("tld").asBuilder().setReservedLists(rl1, rl2).build());

    assertThat(getReservationTypes("lol", "tld")).containsExactly(FULLY_BLOCKED);
    assertThat(getReservationTypes("cat", "tld")).containsExactly(FULLY_BLOCKED);
    assertThat(getReservationTypes("roflcopter", "tld")).containsExactly(FULLY_BLOCKED);
    assertThat(getReservationTypes("snowcrash", "tld")).containsExactly(FULLY_BLOCKED);
    assertThat(getReservationTypes("doge", "tld")).isEmpty();
    assertThat(reservedListChecks)
        .hasValueForLabels(1, "tld", "0", "(none)", "(none)")
        .and()
        .hasValueForLabels(2, "tld", "1", "reserved1", FULLY_BLOCKED.toString())
        .and()
        .hasValueForLabels(2, "tld", "1", "reserved2", FULLY_BLOCKED.toString())
        .and()
        .hasNoOtherValues();
    assertThat(reservedListProcessingTime)
        .hasAnyValueForLabels("tld", "0", "(none)", "(none)")
        .and()
        .hasAnyValueForLabels("tld", "1", "reserved1", FULLY_BLOCKED.toString())
        .and()
        .hasAnyValueForLabels("tld", "1", "reserved2", FULLY_BLOCKED.toString())
        .and()
        .hasNoOtherValues();
    assertThat(reservedListHits)
        .hasValueForLabels(2, "tld", "reserved1", FULLY_BLOCKED.toString())
        .and()
        .hasValueForLabels(2, "tld", "reserved2", FULLY_BLOCKED.toString())
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testGetReservationTypes_returnsAllReservationTypesFromMultipleListsForTheSameLabel() {
    ReservedList rl1 =
        persistReservedList("reserved1", "lol,NAME_COLLISION # yup", "cat,FULLY_BLOCKED");
    ReservedList rl2 =
        persistReservedList("reserved2", "lol,ALLOWED_IN_SUNRISE", "snowcrash,FULLY_BLOCKED");
    createTld("tld");
    persistResource(Registry.get("tld").asBuilder().setReservedLists(rl1, rl2).build());

    assertThat(getReservationTypes("lol", "tld"))
        .containsExactly(NAME_COLLISION, ALLOWED_IN_SUNRISE);
    assertThat(getReservationTypes("cat", "tld")).containsExactly(FULLY_BLOCKED);
    assertThat(getReservationTypes("snowcrash", "tld")).containsExactly(FULLY_BLOCKED);
  }

  @Test
  void testGetReservationTypes_worksAfterReservedListRemovedUsingSet() {
    ReservedList rl1 = persistReservedList(
        "reserved1", "lol,FULLY_BLOCKED", "cat,FULLY_BLOCKED");
    ReservedList rl2 = persistReservedList(
        "reserved2", "roflcopter,FULLY_BLOCKED", "snowcrash,FULLY_BLOCKED");
    createTld("tld");
    persistResource(Registry.get("tld").asBuilder().setReservedLists(rl1, rl2).build());
    assertThat(getReservationTypes("roflcopter", "tld")).containsExactly(FULLY_BLOCKED);
    persistResource(Registry.get("tld").asBuilder().setReservedLists(rl1).build());
    assertWithMessage(
            "roflcopter.tld should be unreserved"
                + " after unsetting the registry's second reserved list")
        .that(getReservationTypes("roflcopter", "tld"))
        .isEmpty();
    assertThat(reservedListChecks)
        .hasValueForLabels(1, "tld", "1", "reserved2", FULLY_BLOCKED.toString())
        .and()
        .hasValueForLabels(1, "tld", "0", "(none)", "(none)")
        .and()
        .hasNoOtherValues();
    assertThat(reservedListProcessingTime)
        .hasAnyValueForLabels("tld", "1", "reserved2", FULLY_BLOCKED.toString())
        .and()
        .hasAnyValueForLabels("tld", "0", "(none)", "(none)")
        .and()
        .hasNoOtherValues();
    assertThat(reservedListHits)
        .hasValueForLabels(1, "tld", "reserved2", FULLY_BLOCKED.toString())
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testGetReservationTypes_combinesMultipleLists() {
    ReservedList rl1 = persistReservedList(
        "reserved1", "lol,NAME_COLLISION", "roflcopter,ALLOWED_IN_SUNRISE");
    ReservedList rl2 = persistReservedList("reserved2", "lol,FULLY_BLOCKED");
    createTld("tld");
    persistResource(Registry.get("tld").asBuilder().setReservedLists(rl1, rl2).build());
    assertThat(getReservationTypes("lol", "tld")).containsExactly(FULLY_BLOCKED, NAME_COLLISION);
    assertThat(getReservationTypes("roflcopter", "tld")).containsExactly(ALLOWED_IN_SUNRISE);
    assertThat(reservedListChecks)
        .hasValueForLabels(1, "tld", "1", "reserved1", ALLOWED_IN_SUNRISE.toString())
        .and()
        .hasValueForLabels(1, "tld", "2", "reserved2", FULLY_BLOCKED.toString())
        .and()
        .hasNoOtherValues();
    assertThat(reservedListProcessingTime)
        .hasAnyValueForLabels("tld", "1", "reserved1", ALLOWED_IN_SUNRISE.toString())
        .and()
        .hasAnyValueForLabels("tld", "2", "reserved2", FULLY_BLOCKED.toString())
        .and()
        .hasNoOtherValues();
    assertThat(reservedListHits)
        .hasValueForLabels(1, "tld", "reserved1", NAME_COLLISION.toString())
        .and()
        .hasValueForLabels(1, "tld", "reserved1", ALLOWED_IN_SUNRISE.toString())
        .and()
        .hasValueForLabels(1, "tld", "reserved2", FULLY_BLOCKED.toString())
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testSave() {
    ReservedList rl = persistReservedList("tld-reserved", "lol,FULLY_BLOCKED # yup");
    createTld("tld");
    persistResource(Registry.get("tld").asBuilder().setReservedLists(rl).build());
    assertThat(getReservationTypes("lol", "tld")).containsExactly(FULLY_BLOCKED);
  }

  @Test
  void testSave_commentsArePersistedCorrectly() {
    ReservedList reservedList = persistReservedList(
        "reserved",
        "trombone,FULLY_BLOCKED  # yup",
        "oysters,FULLY_BLOCKED #  this is a loooong comment",
        "nullcomment,ALLOWED_IN_SUNRISE  #");
    assertThat(reservedList.getReservedListEntries()).hasSize(3);

    ReservedListEntry trombone = reservedList.getReservedListEntries().get("trombone");
    assertThat(trombone.label).isEqualTo("trombone");
    assertThat(trombone.reservationType).isEqualTo(FULLY_BLOCKED);
    assertThat(trombone.comment).isEqualTo("yup");

    ReservedListEntry oysters = reservedList.getReservedListEntries().get("oysters");
    assertThat(oysters.label).isEqualTo("oysters");
    assertThat(oysters.reservationType).isEqualTo(FULLY_BLOCKED);
    assertThat(oysters.comment).isEqualTo("this is a loooong comment");

    ReservedListEntry nullComment = reservedList.getReservedListEntries().get("nullcomment");
    assertThat(nullComment.label).isEqualTo("nullcomment");
    assertThat(nullComment.reservationType).isEqualTo(ALLOWED_IN_SUNRISE);
    assertThat(nullComment.comment).isEmpty();
  }

  @Test
  void testIsInUse_returnsTrueWhenInUse() {
    ReservedList rl = persistReservedList("reserved", "trombone,FULLY_BLOCKED");
    persistResource(Registry.get("tld").asBuilder().setReservedLists(ImmutableSet.of(rl)).build());
    assertThat(rl.isInUse()).isTrue();
  }

  @Test
  void testIsInUse_returnsFalseWhenNotInUse() {
    ReservedList rl = persistReservedList("reserved", "trombone,FULLY_BLOCKED");
    assertThat(rl.isInUse()).isFalse();
  }

  @Test
  void testSetFromInputLines() {
    ReservedList reservedList = persistReservedList("reserved", "trombone,FULLY_BLOCKED");
    assertThat(ReservedList.get("reserved").get().getReservedListEntries()).hasSize(1);
    reservedList = reservedList.asBuilder()
        .setReservedListMapFromLines(
            ImmutableList.of("trombone,FULLY_BLOCKED", "trombone2,FULLY_BLOCKED"))
        .build();
    assertThat(reservedList.getReservedListEntries()).hasSize(2);
  }

  @Test
  void testAsBuilderReturnsIdenticalReservedList() {
    ReservedList original = persistReservedList("tld-reserved-cloning", "trombone,FULLY_BLOCKED");
    ReservedList clone = original.asBuilder().build();
    assertThat(clone.getName()).isEqualTo("tld-reserved-cloning");
    assertThat(clone.creationTime).isEqualTo(original.creationTime);
    assertThat(clone.lastUpdateTime).isEqualTo(original.lastUpdateTime);
    assertThat(clone.parent).isEqualTo(original.parent);
    assertThat(original.getReservedListEntries()).isEqualTo(clone.getReservedListEntries());
  }

  @Test
  void testSave_badSyntax() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> persistReservedList("tld", "lol,FULLY_BLOCKED,e,e # yup"));
    assertThat(thrown).hasMessageThat().contains("Could not parse line in reserved list");
  }

  @Test
  void testSave_badReservationType() {
    assertThrows(
        IllegalArgumentException.class, () -> persistReservedList("tld", "lol,FULLY_BLOCKZ # yup"));
  }

  @Test
  void testParse_cannotIncludeDuplicateLabels() {
    ReservedList rl = new ReservedList.Builder().setName("blah").build();
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                rl.parse(
                    ImmutableList.of(
                        "lol,FULLY_BLOCKED",
                        "rofl,FULLY_BLOCKED",
                        "paper,FULLY_BLOCKED",
                        "wood,FULLY_BLOCKED",
                        "lol,FULLY_BLOCKED")));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "List 'blah' cannot contain duplicate labels. Dupes (with counts) were: [lol x 2]");
  }

  @Test
  void testValidation_labelMustBeLowercase() {
    Exception e =
        assertThrows(
            IllegalArgumentException.class,
            () -> ReservedListEntry.create("UPPER.tld", FULLY_BLOCKED, null));
    assertThat(e).hasMessageThat().contains("must be in puny-coded, lower-case form");
  }

  @Test
  void testValidation_labelMustBePunyCoded() {
    Exception e =
        assertThrows(
            IllegalArgumentException.class,
            () -> ReservedListEntry.create("lower.みんな", FULLY_BLOCKED, null));
    assertThat(e).hasMessageThat().contains("must be in puny-coded, lower-case form");
  }
}
