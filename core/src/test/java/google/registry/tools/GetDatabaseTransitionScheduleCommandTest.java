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
import google.registry.model.ofy.Ofy;
import google.registry.testing.InjectExtension;
import google.registry.testing.SetClockExtension;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link GetDatabaseTransitionScheduleCommand} */
public class GetDatabaseTransitionScheduleCommandTest
    extends CommandTestCase<GetDatabaseTransitionScheduleCommand> {

  @Order(value = Order.DEFAULT - 3)
  @RegisterExtension
  final SetClockExtension setClockExtension =
      new SetClockExtension(fakeClock, "1984-12-21T06:07:08.789Z");

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  @BeforeEach
  void beforeEach() {
    inject.setStaticField(Ofy.class, "clock", fakeClock);
  }

  @Test
  void testSuccess() throws Exception {
    TimedTransitionProperty<PrimaryDatabase, PrimaryDatabaseTransition> databaseTransitions =
        TimedTransitionProperty.fromValueMap(
            ImmutableSortedMap.of(START_OF_TIME, PrimaryDatabase.DATASTORE),
            PrimaryDatabaseTransition.class);
    DatabaseTransitionSchedule schedule =
        DatabaseTransitionSchedule.create(
            TransitionId.SIGNED_MARK_REVOCATION_LIST, databaseTransitions);
    ofyTm().transactNew(() -> ofyTm().put(schedule));
    runCommand("SIGNED_MARK_REVOCATION_LIST");
    assertStdoutIs(
        "SIGNED_MARK_REVOCATION_LIST(last updated at 1984-12-21T06:07:08.789Z):"
            + " {1970-01-01T00:00:00.000Z=DATASTORE}\n");
  }

  @Test
  void testSuccess_multipleArguments() throws Exception {
    TimedTransitionProperty<PrimaryDatabase, PrimaryDatabaseTransition> databaseTransitions =
        TimedTransitionProperty.fromValueMap(
            ImmutableSortedMap.of(START_OF_TIME, PrimaryDatabase.DATASTORE),
            PrimaryDatabaseTransition.class);
    DatabaseTransitionSchedule schedule =
        DatabaseTransitionSchedule.create(TransitionId.DOMAIN_LABEL_LISTS, databaseTransitions);
    ofyTm().transactNew(() -> ofyTm().put(schedule));
    fakeClock.advanceOneMilli(); // Now 1984-12-21T06:07:08.790Z
    TimedTransitionProperty<PrimaryDatabase, PrimaryDatabaseTransition> databaseTransitions2 =
        TimedTransitionProperty.fromValueMap(
            ImmutableSortedMap.of(
                START_OF_TIME,
                PrimaryDatabase.DATASTORE,
                DateTime.parse("2020-10-01T00:00:00Z"),
                PrimaryDatabase.CLOUD_SQL),
            PrimaryDatabaseTransition.class);
    DatabaseTransitionSchedule schedule2 =
        DatabaseTransitionSchedule.create(
            TransitionId.SIGNED_MARK_REVOCATION_LIST, databaseTransitions2);
    ofyTm().transactNew(() -> ofyTm().put(schedule2));
    runCommand("DOMAIN_LABEL_LISTS", "SIGNED_MARK_REVOCATION_LIST");
    assertStdoutIs(
        "DOMAIN_LABEL_LISTS(last updated at 1984-12-21T06:07:08.789Z):"
            + " {1970-01-01T00:00:00.000Z=DATASTORE}\n"
            + "SIGNED_MARK_REVOCATION_LIST(last updated at 1984-12-21T06:07:08.790Z):"
            + " {1970-01-01T00:00:00.000Z=DATASTORE, 2020-10-01T00:00:00.000Z=CLOUD_SQL}\n");
  }

  @Test
  void testFailure_scheduleDoesNotExist() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> runCommand("SIGNED_MARK_REVOCATION_LIST"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("A database transition schedule for SIGNED_MARK_REVOCATION_LIST does not exist");
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
        DatabaseTransitionSchedule.create(TransitionId.DOMAIN_LABEL_LISTS, databaseTransitions);
    ofyTm().transactNew(() -> ofyTm().put(schedule));
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommand("DOMAIN_LABEL_LISTS", "SIGNED_MARK_REVOCATION_LIST"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("A database transition schedule for SIGNED_MARK_REVOCATION_LIST does not exist");
  }
}
