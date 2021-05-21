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
import static google.registry.model.common.DatabaseMigrationStateWrapper.MigrationState.DATASTORE_ONLY;
import static google.registry.model.common.DatabaseMigrationStateWrapper.MigrationState.DATASTORE_PRIMARY;
import static google.registry.model.common.DatabaseMigrationStateWrapper.MigrationState.DATASTORE_PRIMARY_READ_ONLY;
import static google.registry.model.common.DatabaseMigrationStateWrapper.MigrationState.SQL_ONLY;
import static google.registry.model.common.DatabaseMigrationStateWrapper.MigrationState.SQL_PRIMARY;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.common.DatabaseMigrationStateWrapper.MigrationState;
import google.registry.testing.AppEngineExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class DatabaseMigrationStateWrapperTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @Test
  void testEmpty_returnsDatastore() {
    assertThat(DatabaseMigrationStateWrapper.get()).isEqualTo(DATASTORE_ONLY);
  }

  @Test
  void testEmpty_canChangeToDatastorePrimary() {
    DatabaseMigrationStateWrapper.set(DATASTORE_PRIMARY);
    assertThat(DatabaseMigrationStateWrapper.get()).isEqualTo(DATASTORE_PRIMARY);
  }

  @Test
  void testValidTransitions() {
    runValidTransition(DATASTORE_ONLY, DATASTORE_PRIMARY);

    runValidTransition(DATASTORE_PRIMARY, DATASTORE_ONLY);
    runValidTransition(DATASTORE_PRIMARY, DATASTORE_PRIMARY_READ_ONLY);

    runValidTransition(DATASTORE_PRIMARY_READ_ONLY, DATASTORE_ONLY);
    runValidTransition(DATASTORE_PRIMARY_READ_ONLY, DATASTORE_PRIMARY);
    runValidTransition(DATASTORE_PRIMARY_READ_ONLY, SQL_PRIMARY);

    runValidTransition(SQL_PRIMARY, DATASTORE_PRIMARY_READ_ONLY);
    runValidTransition(SQL_PRIMARY, SQL_ONLY);

    runValidTransition(SQL_ONLY, SQL_PRIMARY);
  }

  @Test
  void testInvalidTransitions() {
    runInvalidTransition(DATASTORE_ONLY, DATASTORE_ONLY);
    runInvalidTransition(DATASTORE_ONLY, DATASTORE_PRIMARY_READ_ONLY);
    runInvalidTransition(DATASTORE_ONLY, SQL_PRIMARY);
    runInvalidTransition(DATASTORE_ONLY, SQL_ONLY);

    runInvalidTransition(DATASTORE_PRIMARY, DATASTORE_PRIMARY);
    runInvalidTransition(DATASTORE_PRIMARY, SQL_PRIMARY);
    runInvalidTransition(DATASTORE_PRIMARY, SQL_ONLY);

    runInvalidTransition(DATASTORE_PRIMARY_READ_ONLY, DATASTORE_PRIMARY_READ_ONLY);
    runInvalidTransition(DATASTORE_PRIMARY_READ_ONLY, SQL_ONLY);

    runInvalidTransition(SQL_PRIMARY, DATASTORE_ONLY);
    runInvalidTransition(SQL_PRIMARY, DATASTORE_PRIMARY);
    runInvalidTransition(SQL_PRIMARY, SQL_PRIMARY);

    runInvalidTransition(SQL_ONLY, DATASTORE_ONLY);
    runInvalidTransition(SQL_ONLY, DATASTORE_PRIMARY);
    runInvalidTransition(SQL_ONLY, DATASTORE_PRIMARY_READ_ONLY);
    runInvalidTransition(SQL_ONLY, SQL_ONLY);
  }

  private static void runValidTransition(MigrationState from, MigrationState to) {
    setStateForced(from);
    DatabaseMigrationStateWrapper.set(to);
    assertThat(DatabaseMigrationStateWrapper.get()).isEqualTo(to);
  }

  private static void runInvalidTransition(MigrationState from, MigrationState to) {
    setStateForced(from);
    assertThat(
            assertThrows(
                IllegalArgumentException.class, () -> DatabaseMigrationStateWrapper.set(to)))
        .hasMessageThat()
        .isEqualTo(
            String.format(
                "Moving from migration state %s to %s is not a valid transition", from, to));
  }

  private static void setStateForced(MigrationState migrationState) {
    DatabaseMigrationStateWrapper wrapper = new DatabaseMigrationStateWrapper(migrationState);
    ofyTm().transact(() -> ofyTm().put(wrapper));
    assertThat(DatabaseMigrationStateWrapper.get()).isEqualTo(migrationState);
  }
}
