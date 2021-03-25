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

import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.config.RegistryConfig.getSingletonCacheRefreshDuration;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSortedMap;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Mapify;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.ImmutableObject;
import google.registry.model.UpdateAutoTimestamp;
import google.registry.model.annotations.InCrossTld;
import google.registry.model.common.TimedTransitionProperty.TimeMapper;
import google.registry.model.common.TimedTransitionProperty.TimedTransition;
import google.registry.model.registry.label.PremiumList;
import google.registry.model.registry.label.ReservedList;
import google.registry.model.smd.SignedMarkRevocationList;
import google.registry.model.tmch.ClaimsListShard;
import google.registry.persistence.VKey;
import google.registry.schema.replay.DatastoreOnlyEntity;
import java.util.Optional;
import javax.annotation.concurrent.Immutable;
import org.joda.time.DateTime;

@Entity
@Immutable
@InCrossTld
public class DatabaseTransitionSchedule extends ImmutableObject implements DatastoreOnlyEntity {

  /**
   * The name of the database to be treated as the primary database. The first entry in the schedule
   * will always be Datastore.
   */
  public enum PrimaryDatabase {
    CLOUD_SQL,
    DATASTORE
  }

  /** The id of the transition schedule. */
  public enum TransitionId {
    /** The schedule for migration of {@link ClaimsListShard} entities. */
    CLAIMS_LIST,
    /** The schedule for the migration of {@link PremiumList} and {@link ReservedList}. */
    DOMAIN_LABEL_LISTS,
    /** The schedule for the migration of the {@link SignedMarkRevocationList} entity. */
    SIGNED_MARK_REVOCATION_LIST,
    /** The schedule for all asynchronously-replayed entities, ones not dually-written. */
    REPLAYED_ENTITIES,
  }

  /**
   * The transition to a specified primary database at a specific point in time, for use in a
   * TimedTransitionProperty.
   */
  @Embed
  public static class PrimaryDatabaseTransition extends TimedTransition<PrimaryDatabase> {
    private PrimaryDatabase primaryDatabase;

    @Override
    protected PrimaryDatabase getValue() {
      return primaryDatabase;
    }

    @Override
    protected void setValue(PrimaryDatabase primaryDatabase) {
      this.primaryDatabase = primaryDatabase;
    }
  }

  @Parent Key<EntityGroupRoot> parent = getCrossTldKey();

  @Id String transitionId;

  /** An automatically managed timestamp of when this schedule was last written to Datastore. */
  UpdateAutoTimestamp lastUpdateTime = UpdateAutoTimestamp.create(null);

  /** A property that tracks the primary database for a dual-read/dual-write database migration. */
  @Mapify(TimeMapper.class)
  TimedTransitionProperty<PrimaryDatabase, PrimaryDatabaseTransition> databaseTransitions =
      TimedTransitionProperty.forMapify(PrimaryDatabase.DATASTORE, PrimaryDatabaseTransition.class);

  /** A cache that loads the {@link DatabaseTransitionSchedule} for a given id. */
  private static final LoadingCache<TransitionId, Optional<DatabaseTransitionSchedule>> CACHE =
      CacheBuilder.newBuilder()
          .expireAfterWrite(
              java.time.Duration.ofMillis(getSingletonCacheRefreshDuration().getMillis()))
          .build(
              new CacheLoader<TransitionId, Optional<DatabaseTransitionSchedule>>() {
                @Override
                public Optional<DatabaseTransitionSchedule> load(TransitionId transitionId) {
                  return DatabaseTransitionSchedule.get(transitionId);
                }
              });

  public static DatabaseTransitionSchedule create(
      TransitionId transitionId,
      TimedTransitionProperty<PrimaryDatabase, PrimaryDatabaseTransition> databaseTransitions) {
    checkNotNull(transitionId, "Id cannot be null");
    checkNotNull(databaseTransitions, "databaseTransitions cannot be null");
    databaseTransitions.checkValidity();
    DatabaseTransitionSchedule instance = new DatabaseTransitionSchedule();
    instance.transitionId = transitionId.name();
    instance.databaseTransitions = databaseTransitions;
    return instance;
  }

  /** Returns the database that is indicated as primary at the given time. */
  public PrimaryDatabase getPrimaryDatabase() {
    return databaseTransitions.getValueAtTime(tm().getTransactionTime());
  }

  /** Returns the database transitions as a map of start time to primary database. */
  public ImmutableSortedMap<DateTime, PrimaryDatabase> getDatabaseTransitions() {
    return databaseTransitions.toValueMap();
  }

  /**
   * Returns the current cached schedule for the given id.
   *
   * <p>WARNING: The schedule returned by this method could be up to 10 minutes out of date.
   */
  public static Optional<DatabaseTransitionSchedule> getCached(TransitionId id) {
    return CACHE.getUnchecked(id);
  }

  /** Returns the schedule for a given id. */
  public static Optional<DatabaseTransitionSchedule> get(TransitionId transitionId) {
    VKey<DatabaseTransitionSchedule> key =
        VKey.create(
            DatabaseTransitionSchedule.class,
            transitionId,
            Key.create(getCrossTldKey(), DatabaseTransitionSchedule.class, transitionId.name()));

    return ofyTm().transact(() -> ofyTm().loadByKeyIfPresent(key));
  }

  @Override
  public String toString() {
    return String.format(
        "%s(last updated at %s): %s",
        transitionId, lastUpdateTime.getTimestamp(), databaseTransitions.toValueMap());
  }
}
