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
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.EntityTestCase;
import google.registry.model.common.DatabaseTransitionSchedule.PrimaryDatabase;
import google.registry.model.common.DatabaseTransitionSchedule.PrimaryDatabaseTransition;
import google.registry.model.common.DatabaseTransitionSchedule.TransitionId;
import org.joda.time.Duration;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DatabaseTransitionSchedule}. */
public class DatabaseTransitionScheduleTest extends EntityTestCase {

  @Test
  void testSuccess_persistence() {
    TimedTransitionProperty<PrimaryDatabase, PrimaryDatabaseTransition> databaseTransitions =
        TimedTransitionProperty.fromValueMap(
            ImmutableSortedMap.of(START_OF_TIME, PrimaryDatabase.DATASTORE),
            PrimaryDatabaseTransition.class);
    DatabaseTransitionSchedule schedule =
        DatabaseTransitionSchedule.create(
            TransitionId.SIGNED_MARK_REVOCATION_LIST, databaseTransitions);
    ofyTm().transactNew(() -> ofyTm().put(schedule));

    assertThat(
            DatabaseTransitionSchedule.get(TransitionId.SIGNED_MARK_REVOCATION_LIST)
                .get()
                .databaseTransitions)
        .isEqualTo(databaseTransitions);
  }

  @Test
  void testFailure_scheduleWithNoStartOfTime() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DatabaseTransitionSchedule.create(
                TransitionId.SIGNED_MARK_REVOCATION_LIST,
                TimedTransitionProperty.fromValueMap(
                    ImmutableSortedMap.of(fakeClock.nowUtc(), PrimaryDatabase.DATASTORE),
                    PrimaryDatabaseTransition.class)));
  }

  @Test
  void testSuccess_getPrimaryDatabase() {
    DatabaseTransitionSchedule schedule =
        DatabaseTransitionSchedule.create(
            TransitionId.SIGNED_MARK_REVOCATION_LIST,
            TimedTransitionProperty.fromValueMap(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    PrimaryDatabase.DATASTORE,
                    fakeClock.nowUtc().plusDays(1),
                    PrimaryDatabase.CLOUD_SQL),
                PrimaryDatabaseTransition.class));
    assertThat(ofyTm().transact(schedule::getPrimaryDatabase)).isEqualTo(PrimaryDatabase.DATASTORE);
    fakeClock.advanceBy(Duration.standardDays(5));
    assertThat(ofyTm().transact(schedule::getPrimaryDatabase)).isEqualTo(PrimaryDatabase.CLOUD_SQL);
  }
}
