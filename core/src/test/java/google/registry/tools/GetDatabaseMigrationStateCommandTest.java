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

import static google.registry.model.common.DatabaseMigrationStateSchedule.DEFAULT_TRANSITION_MAP;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;

/** Tests for {@link GetDatabaseMigrationStateCommand}. */
@DualDatabaseTest
public class GetDatabaseMigrationStateCommandTest
    extends CommandTestCase<GetDatabaseMigrationStateCommand> {

  @AfterEach
  void afterEach() {
    DatabaseHelper.removeDatabaseMigrationSchedule();
  }

  @TestOfyAndSql
  void testInitial_returnsDatastoreOnly() throws Exception {
    runCommand();
    assertStdoutIs(
        String.format("Current migration schedule: %s\n", DEFAULT_TRANSITION_MAP.toValueMap()));
  }

  @TestOfyAndSql
  void testFullSchedule() throws Exception {
    DateTime now = fakeClock.nowUtc();
    ImmutableSortedMap<DateTime, MigrationState> transitions =
        ImmutableSortedMap.of(
            START_OF_TIME,
            MigrationState.DATASTORE_ONLY,
            now.plusHours(1),
            MigrationState.DATASTORE_PRIMARY,
            now.plusHours(2),
            MigrationState.DATASTORE_PRIMARY_READ_ONLY,
            now.plusHours(3),
            MigrationState.SQL_PRIMARY,
            now.plusHours(4),
            MigrationState.SQL_ONLY);
    ofyTm().transact(() -> DatabaseMigrationStateSchedule.set(transitions));
    runCommand();
    assertStdoutIs(String.format("Current migration schedule: %s\n", transitions));
  }
}
