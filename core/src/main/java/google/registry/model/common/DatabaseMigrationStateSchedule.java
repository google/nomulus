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
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryEnvironment;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.common.TimedTransitionProperty.TimedTransition;
import google.registry.model.replay.SqlOnlyEntity;
import java.time.Duration;
import javax.persistence.Entity;
import javax.persistence.PersistenceException;
import org.joda.time.DateTime;

/**
 * A wrapper object representing the stage-to-time mapping of the Registry 3.0 Cloud SQL migration.
 *
 * <p>The entity is stored in SQL throughout the entire migration so as to have a single point of
 * access.
 */
@DeleteAfterMigration
@Entity
public class DatabaseMigrationStateSchedule extends CrossTldSingleton implements SqlOnlyEntity {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public enum PrimaryDatabase {
    CLOUD_SQL,
    DATASTORE
  }

  public enum ReplayDirection {
    NO_REPLAY,
    DATASTORE_TO_SQL,
    SQL_TO_DATASTORE
  }

  /**
   * The current phase of the migration plus information about which database to use and whether or
   * not the phase is read-only.
   */
  public enum MigrationState {
    DATASTORE_ONLY(PrimaryDatabase.DATASTORE, false, ReplayDirection.NO_REPLAY),
    DATASTORE_PRIMARY(PrimaryDatabase.DATASTORE, false, ReplayDirection.DATASTORE_TO_SQL),
    DATASTORE_PRIMARY_READ_ONLY(PrimaryDatabase.DATASTORE, true, ReplayDirection.DATASTORE_TO_SQL),
    SQL_PRIMARY_READ_ONLY(PrimaryDatabase.CLOUD_SQL, true, ReplayDirection.SQL_TO_DATASTORE),
    SQL_PRIMARY(PrimaryDatabase.CLOUD_SQL, false, ReplayDirection.SQL_TO_DATASTORE),
    SQL_ONLY(PrimaryDatabase.CLOUD_SQL, false, ReplayDirection.NO_REPLAY);

    private final PrimaryDatabase primaryDatabase;
    private final boolean isReadOnly;
    private final ReplayDirection replayDirection;

    public PrimaryDatabase getPrimaryDatabase() {
      return primaryDatabase;
    }

    public boolean isReadOnly() {
      return isReadOnly;
    }

    public ReplayDirection getReplayDirection() {
      return replayDirection;
    }

    MigrationState(
        PrimaryDatabase primaryDatabase, boolean isReadOnly, ReplayDirection replayDirection) {
      this.primaryDatabase = primaryDatabase;
      this.isReadOnly = isReadOnly;
      this.replayDirection = replayDirection;
    }
  }

  public static class MigrationStateTransition extends TimedTransition<MigrationState> {
    private MigrationState migrationState;

    @Override
    public MigrationState getValue() {
      return migrationState;
    }

    @Override
    protected void setValue(MigrationState migrationState) {
      this.migrationState = migrationState;
    }
  }

  /**
   * Cache of the current migration schedule. The key is meaningless; this is essentially a memoized
   * Supplier that can be reset for testing purposes and after writes.
   */
  @VisibleForTesting
  public static final LoadingCache<
          Class<DatabaseMigrationStateSchedule>,
          TimedTransitionProperty<MigrationState, MigrationStateTransition>>
      // Each instance should cache the migration schedule for five minutes before reloading
      CACHE =
          CacheBuilder.newBuilder()
              .expireAfterWrite(Duration.ofMinutes(5))
              .build(
                  new CacheLoader<
                      Class<DatabaseMigrationStateSchedule>,
                      TimedTransitionProperty<MigrationState, MigrationStateTransition>>() {
                    @Override
                    public TimedTransitionProperty<MigrationState, MigrationStateTransition> load(
                        Class<DatabaseMigrationStateSchedule> unused) {
                      return DatabaseMigrationStateSchedule.getUncached();
                    }
                  });

  // Restrictions on the state transitions, e.g. no going from DATASTORE_ONLY to SQL_ONLY
  private static final ImmutableMultimap<MigrationState, MigrationState> VALID_STATE_TRANSITIONS =
      createValidStateTransitions();

  /**
   * The valid state transitions. Generally, one can advance the state one step or move backward any
   * number of steps, as long as the step we're moving back to has the same primary database as the
   * one we're in. Otherwise, we must move to the corresponding READ_ONLY stage first.
   */
  private static ImmutableMultimap<MigrationState, MigrationState> createValidStateTransitions() {
    ImmutableMultimap.Builder<MigrationState, MigrationState> builder =
        new ImmutableMultimap.Builder<MigrationState, MigrationState>()
            .put(MigrationState.DATASTORE_ONLY, MigrationState.DATASTORE_PRIMARY)
            .putAll(
                MigrationState.DATASTORE_PRIMARY,
                MigrationState.DATASTORE_ONLY,
                MigrationState.DATASTORE_PRIMARY_READ_ONLY)
            .putAll(
                MigrationState.DATASTORE_PRIMARY_READ_ONLY,
                MigrationState.DATASTORE_ONLY,
                MigrationState.DATASTORE_PRIMARY,
                MigrationState.SQL_PRIMARY_READ_ONLY,
                MigrationState.SQL_PRIMARY)
            .putAll(
                MigrationState.SQL_PRIMARY_READ_ONLY,
                MigrationState.DATASTORE_PRIMARY_READ_ONLY,
                MigrationState.SQL_PRIMARY)
            .putAll(
                MigrationState.SQL_PRIMARY,
                MigrationState.SQL_PRIMARY_READ_ONLY,
                MigrationState.SQL_ONLY)
            .putAll(
                MigrationState.SQL_ONLY,
                MigrationState.SQL_PRIMARY_READ_ONLY,
                MigrationState.SQL_PRIMARY);
    // In addition, we can always transition from a state to itself (useful when updating the map).
    for (MigrationState migrationState : MigrationState.values()) {
      builder.put(migrationState, migrationState);
    }
    return builder.build();
  }

  // Default map to return if we have never saved any -- only use Datastore.
  @VisibleForTesting
  public static final TimedTransitionProperty<MigrationState, MigrationStateTransition>
      DEFAULT_TRANSITION_MAP =
          TimedTransitionProperty.fromValueMap(
              ImmutableSortedMap.of(START_OF_TIME, MigrationState.DATASTORE_ONLY),
              MigrationStateTransition.class);

  @VisibleForTesting
  public TimedTransitionProperty<MigrationState, MigrationStateTransition> migrationTransitions =
      TimedTransitionProperty.forMapify(
          MigrationState.DATASTORE_ONLY, MigrationStateTransition.class);

  // Required for Objectify initialization
  private DatabaseMigrationStateSchedule() {}

  @VisibleForTesting
  public DatabaseMigrationStateSchedule(
      TimedTransitionProperty<MigrationState, MigrationStateTransition> migrationTransitions) {
    this.migrationTransitions = migrationTransitions;
  }

  /** Sets and persists to SQL the provided migration transition schedule. */
  public static void set(ImmutableSortedMap<DateTime, MigrationState> migrationTransitionMap) {
    jpaTm().assertInTransaction();
    TimedTransitionProperty<MigrationState, MigrationStateTransition> transitions =
        TimedTransitionProperty.make(
            migrationTransitionMap,
            MigrationStateTransition.class,
            VALID_STATE_TRANSITIONS,
            "validStateTransitions",
            MigrationState.DATASTORE_ONLY,
            "migrationTransitionMap must start with DATASTORE_ONLY");
    validateTransitionAtCurrentTime(transitions);
    jpaTm().putIgnoringReadOnlyWithoutBackup(new DatabaseMigrationStateSchedule(transitions));
    CACHE.invalidateAll();
  }

  /** Loads the currently-set migration schedule from the cache, or the default if none exists. */
  public static TimedTransitionProperty<MigrationState, MigrationStateTransition> get() {
    return CACHE.getUnchecked(DatabaseMigrationStateSchedule.class);
  }

  /** Returns the database migration status at the given time. */
  public static MigrationState getValueAtTime(DateTime dateTime) {
    return get().getValueAtTime(dateTime);
  }

  /** Loads the currently-set migration schedule from SQL, or the default if none exists. */
  @VisibleForTesting
  static TimedTransitionProperty<MigrationState, MigrationStateTransition> getUncached() {
    return jpaTm()
        .transactWithoutBackup(
            () -> {
              try {
                return jpaTm()
                    .loadSingleton(DatabaseMigrationStateSchedule.class)
                    .map(s -> s.migrationTransitions)
                    .orElse(DEFAULT_TRANSITION_MAP);
              } catch (PersistenceException e) {
                if (!RegistryEnvironment.get().equals(RegistryEnvironment.UNITTEST)) {
                  throw e;
                }
                logger.atWarning().withCause(e).log(
                    "Error when retrieving migration schedule; this should only happen in tests.");
                return DEFAULT_TRANSITION_MAP;
              }
            });
  }

  /**
   * A provided map of transitions may be valid by itself (i.e. it shifts states properly, doesn't
   * skip states, and doesn't backtrack incorrectly) while still being invalid. In addition to the
   * transitions in the map being valid, the single transition from the current map at the current
   * time to the new map at the current time time must also be valid.
   */
  private static void validateTransitionAtCurrentTime(
      TimedTransitionProperty<MigrationState, MigrationStateTransition> newTransitions) {
    MigrationState currentValue = getUncached().getValueAtTime(jpaTm().getTransactionTime());
    MigrationState nextCurrentValue = newTransitions.getValueAtTime(jpaTm().getTransactionTime());
    checkArgument(
        VALID_STATE_TRANSITIONS.get(currentValue).contains(nextCurrentValue),
        "Cannot transition from current state-as-of-now %s to new state-as-of-now %s",
        currentValue,
        nextCurrentValue);
  }
}
