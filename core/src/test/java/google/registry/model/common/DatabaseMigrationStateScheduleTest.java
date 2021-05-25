// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.common;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState.DATASTORE_ONLY;
import static google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState.DATASTORE_PRIMARY;
import static google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState.DATASTORE_PRIMARY_READ_ONLY;
import static google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState.SQL_ONLY;
import static google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState.SQL_PRIMARY;
import static google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState.SQL_PRIMARY_READ_ONLY;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.EntityTestCase;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DatabaseMigrationStateScheduleTest extends EntityTestCase {

  @BeforeEach
  void beforeEach() {
    fakeClock.setAutoIncrementByOneMilli();
  }

  @Test
  void testEmpty_returnsDatastoreOnlyMap() {
    assertThat(DatabaseMigrationStateSchedule.getUncached())
        .isEqualTo(DatabaseMigrationStateSchedule.DEFAULT_TRANSITION_MAP);
  }

  @Test
  void testValidTransitions() {
    // First, verify that no-ops are safe
    for (MigrationState migrationState : MigrationState.values()) {
      runValidTransition(migrationState, migrationState);
    }

    // Next, the transitions that will actually cause a change
    runValidTransition(DATASTORE_ONLY, DATASTORE_PRIMARY);

    runValidTransition(DATASTORE_PRIMARY, DATASTORE_ONLY);
    runValidTransition(DATASTORE_PRIMARY, DATASTORE_PRIMARY_READ_ONLY);

    runValidTransition(DATASTORE_PRIMARY_READ_ONLY, DATASTORE_ONLY);
    runValidTransition(DATASTORE_PRIMARY_READ_ONLY, DATASTORE_PRIMARY);
    runValidTransition(DATASTORE_PRIMARY_READ_ONLY, SQL_PRIMARY_READ_ONLY);
    runValidTransition(DATASTORE_PRIMARY_READ_ONLY, SQL_PRIMARY);

    runValidTransition(SQL_PRIMARY_READ_ONLY, DATASTORE_PRIMARY_READ_ONLY);
    runValidTransition(SQL_PRIMARY_READ_ONLY, SQL_PRIMARY);

    runValidTransition(SQL_PRIMARY, SQL_PRIMARY_READ_ONLY);
    runValidTransition(SQL_PRIMARY, SQL_ONLY);

    runValidTransition(SQL_ONLY, SQL_PRIMARY);
  }

  @Test
  void testInvalidTransitions() {
    runInvalidTransition(DATASTORE_ONLY, DATASTORE_PRIMARY_READ_ONLY);
    runInvalidTransition(DATASTORE_ONLY, SQL_PRIMARY_READ_ONLY);
    runInvalidTransition(DATASTORE_ONLY, SQL_PRIMARY);
    runInvalidTransition(DATASTORE_ONLY, SQL_ONLY);

    runInvalidTransition(DATASTORE_PRIMARY, SQL_PRIMARY_READ_ONLY);
    runInvalidTransition(DATASTORE_PRIMARY, SQL_PRIMARY);
    runInvalidTransition(DATASTORE_PRIMARY, SQL_ONLY);

    runInvalidTransition(DATASTORE_PRIMARY_READ_ONLY, SQL_ONLY);

    runInvalidTransition(SQL_PRIMARY_READ_ONLY, DATASTORE_ONLY);
    runInvalidTransition(SQL_PRIMARY_READ_ONLY, DATASTORE_PRIMARY);
    runInvalidTransition(SQL_PRIMARY_READ_ONLY, SQL_ONLY);

    runInvalidTransition(SQL_PRIMARY, DATASTORE_ONLY);
    runInvalidTransition(SQL_PRIMARY, DATASTORE_PRIMARY);
    runInvalidTransition(SQL_PRIMARY, DATASTORE_PRIMARY_READ_ONLY);

    runInvalidTransition(SQL_ONLY, DATASTORE_ONLY);
    runInvalidTransition(SQL_ONLY, DATASTORE_PRIMARY);
    runInvalidTransition(SQL_ONLY, DATASTORE_PRIMARY_READ_ONLY);
  }

  @Test
  void testFailure_newMapImpliesInvalidChangeNow() {
    DateTime startTime = fakeClock.nowUtc();
    fakeClock.advanceBy(Duration.standardHours(6));

    // The new map is valid by itself, but not with the current state of DATASTORE_ONLY because the
    // new map implies that the current state is DATASTORE_PRIMARY_READ_ONLY
    ImmutableSortedMap<DateTime, MigrationState> nowInvalidMap =
        ImmutableSortedMap.<DateTime, MigrationState>naturalOrder()
            .put(START_OF_TIME, DATASTORE_ONLY)
            .put(startTime.plusHours(1), DATASTORE_PRIMARY)
            .put(startTime.plusHours(2), DATASTORE_PRIMARY_READ_ONLY)
            .build();
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> ofyTm().transact(() -> DatabaseMigrationStateSchedule.set(nowInvalidMap))))
        .hasMessageThat()
        .isEqualTo(
            "Cannot transition from current state-as-of-now DATASTORE_ONLY "
                + "to new state-as-of-now DATASTORE_PRIMARY_READ_ONLY");
  }

  @Test
  void testFailure_notInTransaction() {
    assertThat(
            assertThrows(
                IllegalStateException.class,
                () ->
                    DatabaseMigrationStateSchedule.set(
                        DatabaseMigrationStateSchedule.DEFAULT_TRANSITION_MAP.toValueMap())))
        .hasMessageThat()
        .isEqualTo("Must be called in a transaction");
  }

  private void runValidTransition(MigrationState from, MigrationState to) {
    ImmutableSortedMap<DateTime, MigrationState> transitions =
        createMapEndingWithTransition(from, to);
    ofyTm().transact(() -> DatabaseMigrationStateSchedule.set(transitions));
    assertThat(DatabaseMigrationStateSchedule.getUncached().toValueMap())
        .containsExactlyEntriesIn(transitions);
  }

  private void runInvalidTransition(MigrationState from, MigrationState to) {
    ImmutableSortedMap<DateTime, MigrationState> transitions =
        createMapEndingWithTransition(from, to);
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> ofyTm().transact(() -> DatabaseMigrationStateSchedule.set(transitions))))
        .hasMessageThat()
        .isEqualTo(
            String.format("validStateTransitions map cannot transition from %s to %s.", from, to));
  }

  // Create a transition map that is valid up to the "from" transition, then add the "to" transition
  private ImmutableSortedMap<DateTime, MigrationState> createMapEndingWithTransition(
      MigrationState from, MigrationState to) {
    ImmutableSortedMap.Builder<DateTime, MigrationState> builder =
        ImmutableSortedMap.naturalOrder();
    builder.put(START_OF_TIME, DATASTORE_ONLY);
    MigrationState[] allMigrationStates = MigrationState.values();
    for (int i = 0; i < allMigrationStates.length; i++) {
      builder.put(fakeClock.nowUtc().plusMinutes(i), allMigrationStates[i]);
      if (allMigrationStates[i].equals(from)) {
        break;
      }
    }
    builder.put(fakeClock.nowUtc().plusDays(1), to);
    return builder.build();
  }
}
