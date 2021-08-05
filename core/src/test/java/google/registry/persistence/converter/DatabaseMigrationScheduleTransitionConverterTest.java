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

package google.registry.persistence.converter;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.ImmutableObject;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationStateTransition;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestExtension;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link DatabaseMigrationScheduleTransitionConverter}. */
public class DatabaseMigrationScheduleTransitionConverterTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpa =
      new JpaTestRules.Builder()
          .withEntityClass(DatabaseMigrationScheduleTransitionConverterTestEntity.class)
          .buildUnitTestRule();

  private static final ImmutableSortedMap<DateTime, MigrationState> values =
      ImmutableSortedMap.of(
          START_OF_TIME,
          MigrationState.DATASTORE_ONLY,
          DateTime.parse("2001-01-01T00:00:00.0Z"),
          MigrationState.DATASTORE_PRIMARY,
          DateTime.parse("2002-01-01T00:00:00.0Z"),
          MigrationState.DATASTORE_PRIMARY_READ_ONLY,
          DateTime.parse("2002-01-02T00:00:00.0Z"),
          MigrationState.SQL_PRIMARY,
          DateTime.parse("2002-01-03T00:00:00.0Z"),
          MigrationState.SQL_ONLY);

  @Test
  void roundTripConversion_returnsSameTimedTransitionProperty() {
    TimedTransitionProperty<MigrationState, MigrationStateTransition> timedTransitionProperty =
        TimedTransitionProperty.fromValueMap(values, MigrationStateTransition.class);
    DatabaseMigrationScheduleTransitionConverterTestEntity testEntity =
        new DatabaseMigrationScheduleTransitionConverterTestEntity(timedTransitionProperty);
    jpaTm().transact(() -> jpaTm().insert(testEntity));
    DatabaseMigrationScheduleTransitionConverterTestEntity persisted =
        jpaTm()
            .transact(
                () ->
                    jpaTm()
                        .getEntityManager()
                        .find(DatabaseMigrationScheduleTransitionConverterTestEntity.class, "id"));
    assertThat(persisted.timedTransitionProperty).containsExactlyEntriesIn(timedTransitionProperty);
  }

  @Entity
  private static class DatabaseMigrationScheduleTransitionConverterTestEntity
      extends ImmutableObject {

    @Id String name = "id";

    TimedTransitionProperty<MigrationState, MigrationStateTransition> timedTransitionProperty;

    private DatabaseMigrationScheduleTransitionConverterTestEntity() {}

    private DatabaseMigrationScheduleTransitionConverterTestEntity(
        TimedTransitionProperty<MigrationState, MigrationStateTransition> timedTransitionProperty) {
      this.timedTransitionProperty = timedTransitionProperty;
    }
  }
}
