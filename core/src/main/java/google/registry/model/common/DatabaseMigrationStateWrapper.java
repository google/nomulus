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

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.annotation.Entity;
import google.registry.model.annotations.InCrossTld;
import google.registry.model.common.DatabaseTransitionSchedule.PrimaryDatabase;
import google.registry.schema.replay.DatastoreOnlyEntity;

/**
 * A wrapper object representing the current stage of the Registry 3.0 Cloud SQL migration.
 *
 * <p>The entity is stored in Datastore throughout the entire migration so as to have a single point
 * of access (avoiding a two-phase commit problem).
 */
@Entity
@InCrossTld
public class DatabaseMigrationStateWrapper extends CrossTldSingleton
    implements DatastoreOnlyEntity {

  /**
   * The current phase of the migration plus information about which database to use and whether or
   * not the phase is read-only.
   */
  public enum MigrationState {
    DATASTORE_ONLY(PrimaryDatabase.DATASTORE, false),
    DATASTORE_PRIMARY(PrimaryDatabase.DATASTORE, false),
    DATASTORE_PRIMARY_READ_ONLY(PrimaryDatabase.DATASTORE, true),
    SQL_PRIMARY(PrimaryDatabase.CLOUD_SQL, false),
    SQL_ONLY(PrimaryDatabase.CLOUD_SQL, false);

    private final PrimaryDatabase primaryDatabase;
    private final boolean readOnly;

    public PrimaryDatabase getPrimaryDatabase() {
      return primaryDatabase;
    }

    public boolean isReadOnly() {
      return readOnly;
    }

    MigrationState(PrimaryDatabase primaryDatabase, boolean readOnly) {
      this.primaryDatabase = primaryDatabase;
      this.readOnly = readOnly;
    }
  }

  private MigrationState migrationState;

  // Required for Objectify initialization
  private DatabaseMigrationStateWrapper() {}

  DatabaseMigrationStateWrapper(MigrationState migrationState) {
    this.migrationState = migrationState;
  }

  // The valid state transitions. Basically, at state N, state N+1 is valid as well as all previous
  // states, with one type of exception: when in either of the SQL states, we can only move back
  // one step so that we can make sure that any modifications have been replayed back to Datastore.
  private static final ImmutableMap<MigrationState, ImmutableSet<MigrationState>>
      VALID_STATE_TRANSITIONS =
          ImmutableMap.of(
              MigrationState.DATASTORE_ONLY,
              ImmutableSet.of(MigrationState.DATASTORE_PRIMARY),
              MigrationState.DATASTORE_PRIMARY,
              ImmutableSet.of(
                  MigrationState.DATASTORE_ONLY, MigrationState.DATASTORE_PRIMARY_READ_ONLY),
              MigrationState.DATASTORE_PRIMARY_READ_ONLY,
              ImmutableSet.of(
                  MigrationState.DATASTORE_ONLY,
                  MigrationState.DATASTORE_PRIMARY,
                  MigrationState.SQL_PRIMARY),
              MigrationState.SQL_PRIMARY,
              ImmutableSet.of(MigrationState.DATASTORE_PRIMARY_READ_ONLY, MigrationState.SQL_ONLY),
              MigrationState.SQL_ONLY,
              ImmutableSet.of(MigrationState.SQL_PRIMARY));

  private static boolean isValidStateTransition(MigrationState from, MigrationState to) {
    return VALID_STATE_TRANSITIONS.get(from).contains(to);
  }

  /** Sets and persists to Datastore the current database migration state. */
  public static void set(MigrationState newState) {
    MigrationState currentState = get();
    checkArgument(
        isValidStateTransition(currentState, newState),
        "Moving from migration state %s to %s is not a valid transition",
        currentState,
        newState);
    DatabaseMigrationStateWrapper wrapper = new DatabaseMigrationStateWrapper(newState);
    ofyTm().transact(() -> ofyTm().put(wrapper));
  }

  /** Retrieves the current state of the migration (or DATASTORE_ONLY if it hasn't started). */
  public static MigrationState get() {
    return ofyTm()
        .transact(
            () ->
                ofyTm()
                    .loadSingleton(DatabaseMigrationStateWrapper.class)
                    .map(s -> s.migrationState)
                    .orElse(MigrationState.DATASTORE_ONLY));
  }
}
