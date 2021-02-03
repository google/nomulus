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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.common.DatabaseTransitionSchedule;
import google.registry.model.common.DatabaseTransitionSchedule.PrimaryDatabase;
import google.registry.model.common.DatabaseTransitionSchedule.PrimaryDatabaseTransition;
import google.registry.model.common.DatabaseTransitionSchedule.TransitionId;
import google.registry.model.common.TimedTransitionProperty;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GetDatabaseTransitionScheduleCommand} */
public class GetDatabaseTransitionScheduleCommandTest
    extends CommandTestCase<GetDatabaseTransitionScheduleCommand> {

  @Test
  void testSuccess() throws Exception {
    TimedTransitionProperty<PrimaryDatabase, PrimaryDatabaseTransition> databaseTransitions =
        TimedTransitionProperty.fromValueMap(
            ImmutableSortedMap.of(START_OF_TIME, PrimaryDatabase.DATASTORE),
            PrimaryDatabaseTransition.class);
    DatabaseTransitionSchedule schedule =
        DatabaseTransitionSchedule.create(TransitionId.TEST, databaseTransitions);
    ofyTm().transactNew(() -> ofyTm().put(schedule));
    runCommand("TEST");
    assertStdoutIs("TEST: {1970-01-01T00:00:00.000Z=DATASTORE}\n");
  }

  @Test
  void testSuccess_multipleArguments() throws Exception {
    fakeClock.setTo(DateTime.parse("2020-10-01T00:00:00Z"));
    TimedTransitionProperty<PrimaryDatabase, PrimaryDatabaseTransition> databaseTransitions =
        TimedTransitionProperty.fromValueMap(
            ImmutableSortedMap.of(START_OF_TIME, PrimaryDatabase.DATASTORE),
            PrimaryDatabaseTransition.class);
    DatabaseTransitionSchedule schedule =
        DatabaseTransitionSchedule.create(TransitionId.TEST, databaseTransitions);
    ofyTm().transactNew(() -> ofyTm().put(schedule));
    TimedTransitionProperty<PrimaryDatabase, PrimaryDatabaseTransition> databaseTransitions2 =
        TimedTransitionProperty.fromValueMap(
            ImmutableSortedMap.of(
                START_OF_TIME,
                PrimaryDatabase.DATASTORE,
                fakeClock.nowUtc(),
                PrimaryDatabase.CLOUD_SQL),
            PrimaryDatabaseTransition.class);
    DatabaseTransitionSchedule schedule2 =
        DatabaseTransitionSchedule.create(
            TransitionId.SIGNED_MARK_REVOCATION_LIST, databaseTransitions2);
    ofyTm().transactNew(() -> ofyTm().put(schedule2));
    runCommand("TEST", "SIGNED_MARK_REVOCATION_LIST");
    assertStdoutIs(
        "TEST: {1970-01-01T00:00:00.000Z=DATASTORE}\n"
            + "SIGNED_MARK_REVOCATION_LIST: {1970-01-01T00:00:00.000Z=DATASTORE,"
            + " 2020-10-01T00:00:00.000Z=CLOUD_SQL}\n");
  }

  @Test
  void testFailure_scheduleDoesNotExist() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommand("TEST"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("A database transition schedule for TEST does not exist");
  }

  @Test
  void testFailure_noIdGiven() {
    assertThrows(ParameterException.class, this::runCommand);
  }

  @Test
  void testFailure_oneScheduleDoesNotExist() {
    TimedTransitionProperty<PrimaryDatabase, PrimaryDatabaseTransition> databaseTransitions =
        TimedTransitionProperty.fromValueMap(
            ImmutableSortedMap.of(START_OF_TIME, PrimaryDatabase.DATASTORE),
            PrimaryDatabaseTransition.class);
    DatabaseTransitionSchedule schedule =
        DatabaseTransitionSchedule.create(TransitionId.TEST, databaseTransitions);
    ofyTm().transactNew(() -> ofyTm().put(schedule));
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommand("TEST", "SIGNED_MARK_REVOCATION_LIST"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("A database transition schedule for SIGNED_MARK_REVOCATION_LIST does not exist");
  }
}
