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
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableSortedMap;
import com.googlecode.objectify.Key;
import google.registry.model.common.DatabaseTransitionSchedule;
import google.registry.model.common.DatabaseTransitionSchedule.PrimaryDatabase;
import google.registry.model.common.DatabaseTransitionSchedule.PrimaryDatabaseTransition;
import google.registry.model.common.DatabaseTransitionSchedule.TransitionId;
import google.registry.model.common.TimedTransitionProperty;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SetDatabaseTransitionScheduleCommand}. */
public class SetDatabaseTransitionScheduleCommandTest
    extends CommandTestCase<SetDatabaseTransitionScheduleCommand> {

  Key<DatabaseTransitionSchedule> key;

  @BeforeEach
  void setup() {
    key = Key.create(getCrossTldKey(), DatabaseTransitionSchedule.class, "test");
    fakeClock.setTo(DateTime.parse("2020-12-01T00:00:00Z"));
  }

  @Test
  void testFailure_noTransitionId() throws Exception {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () -> runCommandForced("--transition_schedule=2021-04-14T00:00:00.000Z=DATASTORE"));
    assertThat(thrown).hasMessageThat().contains("--transition_id");
  }

  @Test
  void testSuccess_currentScheduleIsEmpty() throws Exception {
    assertThat(ofy().load().key(key).now()).isNull();
    runCommandForced(
        "--transition_id=SIGNED_MARK_REVOCATION_LIST",
        "--transition_schedule=1970-01-01T00:00:00.000Z=DATASTORE");
    assertThat(
            ofyTm()
                .transact(
                    () ->
                        DatabaseTransitionSchedule.get(TransitionId.SIGNED_MARK_REVOCATION_LIST)
                            .get()
                            .getPrimaryDatabase()))
        .isEqualTo(PrimaryDatabase.DATASTORE);
    assertThat(command.prompt()).contains("Create DatabaseTransitionSchedule");
  }

  @Test
  void testSuccess() throws Exception {
    ImmutableSortedMap<DateTime, PrimaryDatabase> transitionMap =
        ImmutableSortedMap.of(
            START_OF_TIME,
            PrimaryDatabase.DATASTORE,
            fakeClock.nowUtc().minusDays(1),
            PrimaryDatabase.CLOUD_SQL);
    persistResource(
        DatabaseTransitionSchedule.create(
            TransitionId.SIGNED_MARK_REVOCATION_LIST,
            TimedTransitionProperty.fromValueMap(transitionMap, PrimaryDatabaseTransition.class)));
    assertThat(
            DatabaseTransitionSchedule.get(TransitionId.SIGNED_MARK_REVOCATION_LIST)
                .get()
                .getDatabaseTransitions())
        .isEqualTo(transitionMap);
    runCommandForced(
        "--transition_id=SIGNED_MARK_REVOCATION_LIST",
        "--transition_schedule=1970-01-01T00:00:00.000Z=DATASTORE,2020-11-30T00:00:00.000Z=CLOUD_SQL,2020-12-06T00:00:00.000Z=DATASTORE");
    ImmutableSortedMap<DateTime, PrimaryDatabase> retrievedTransitionMap =
        ofyTm()
            .transact(
                () ->
                    DatabaseTransitionSchedule.get(TransitionId.SIGNED_MARK_REVOCATION_LIST)
                        .get()
                        .getDatabaseTransitions());
    assertThat(retrievedTransitionMap)
        .containsExactly(
            START_OF_TIME,
            PrimaryDatabase.DATASTORE,
            fakeClock.nowUtc().minusDays(1),
            PrimaryDatabase.CLOUD_SQL,
            fakeClock.nowUtc().plusDays(5),
            PrimaryDatabase.DATASTORE);
    fakeClock.advanceBy(Duration.standardDays(5));
    assertThat(
            ofyTm()
                .transact(
                    () ->
                        DatabaseTransitionSchedule.get(TransitionId.SIGNED_MARK_REVOCATION_LIST)
                            .get()
                            .getPrimaryDatabase()))
        .isEqualTo(PrimaryDatabase.DATASTORE);
    assertThat(command.prompt()).contains("Update DatabaseTransitionSchedule");
  }
}
