// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.smd;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.isEmpty;
import static google.registry.model.DatabaseMigrationUtils.suppressExceptionUnlessInTest;
import static google.registry.model.common.DatabaseTransitionSchedule.PrimaryDatabase.DATASTORE;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.allocateId;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.smd.SignedMarkRevocationList.SHARD_SIZE;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.CollectionUtils.isNullOrEmpty;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import com.google.common.flogger.FluentLogger;
import google.registry.model.DatabaseMigrationUtils;
import google.registry.model.common.DatabaseTransitionSchedule.PrimaryDatabase;
import google.registry.model.common.DatabaseTransitionSchedule.TransitionId;
import google.registry.util.CollectionUtils;
import java.util.Map;
import java.util.Optional;
import org.joda.time.DateTime;

public class SignedMarkRevocationListDao {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * Loads the {@link SignedMarkRevocationList}.
   *
   * <p>Loads the list from the specified primary database, and attempts to load from the secondary
   * database. If the load the secondary database fails, or the list from the secondary database
   * does not match the list from the primary database, the error will be logged but no exception
   * will be thrown.
   */
  static SignedMarkRevocationList load() {
    PrimaryDatabase primaryDatabase =
        tm().transactNew(
                () ->
                    DatabaseMigrationUtils.getPrimaryDatabase(
                        TransitionId.SIGNED_MARK_REVOCATION_LIST));
    Optional<SignedMarkRevocationList> primaryList =
        primaryDatabase.equals(DATASTORE) ? loadFromDatastore() : loadFromCloudSql();
    if (!primaryList.isPresent()) {
      throw new IllegalStateException(
          String.format(
              "SignedMarkRevocationList not found in the primary database (%s).",
              primaryDatabase.name()));
    }
    suppressExceptionUnlessInTest(
        () -> loadAndCompare(primaryDatabase, primaryList.get()),
        String.format(
            "Error loading and comparing the SignedMarkRevocationList from the secondary database"
                + " (%s).",
            primaryDatabase.equals(DATASTORE) ? "Cloud SQL" : "Datastore"));
    return primaryList.get();
  }

  /**
   * Loads the list from the secondary database and compares it to the list from the primary
   * database.
   */
  private static void loadAndCompare(
      PrimaryDatabase primaryDatabase, SignedMarkRevocationList primaryList) {
    Optional<SignedMarkRevocationList> secondaryList =
        primaryDatabase.equals(DATASTORE) ? loadFromCloudSql() : loadFromDatastore();
    if (secondaryList.isPresent() && !isNullOrEmpty(secondaryList.get().revokes)) {
      MapDifference<String, DateTime> diff =
          Maps.difference(primaryList.revokes, secondaryList.get().revokes);
      if (!diff.areEqual()) {
        if (diff.entriesDiffering().size() > 10) {
          String message =
              String.format(
                  "Unequal SignedMarkRevocationList detected, %s list with revision id"
                      + " %d has %d different records than the current primary database list.",
                  primaryDatabase.equals(DATASTORE) ? "Cloud SQL" : "Datastore",
                  secondaryList.get().revisionId,
                  diff.entriesDiffering().size());
          throw new IllegalStateException(message);
        } else {
          StringBuilder diffMessage =
              new StringBuilder("Unequal SignedMarkRevocationList detected:\n");
          diff.entriesDiffering()
              .forEach(
                  (label, valueDiff) ->
                      diffMessage.append(
                          String.format(
                              "SMD %s has key %s in %s and key %s in secondary database.\n",
                              label,
                              valueDiff.leftValue(),
                              primaryDatabase.name(),
                              valueDiff.rightValue())));
          throw new IllegalStateException(diffMessage.toString());
        }
      }
    } else {
      if (primaryList.size() != 0) {
        throw new IllegalStateException(
            String.format(
                "SignedMarkRevocationList in %s is empty while it is not empty in the primary"
                    + " database.",
                primaryDatabase.equals(DATASTORE) ? "Cloud SQL" : "Datastore"));
      }
    }
  }

  /** Loads the shards from Datastore and combines them into one list. */
  private static Optional<SignedMarkRevocationList> loadFromDatastore() {
    return tm().transactNewReadOnly(
            () -> {
              Iterable<SignedMarkRevocationList> shards =
                  ofy().load().type(SignedMarkRevocationList.class).ancestor(getCrossTldKey());
              DateTime creationTime =
                  isEmpty(shards)
                      ? START_OF_TIME
                      : checkNotNull(Iterables.get(shards, 0).creationTime, "creationTime");
              ImmutableMap.Builder<String, DateTime> revokes = new ImmutableMap.Builder<>();
              for (SignedMarkRevocationList shard : shards) {
                revokes.putAll(shard.revokes);
                checkState(
                    creationTime.equals(shard.creationTime),
                    "Inconsistent creation times in Datastore shard: %s vs. %s",
                    creationTime,
                    shard.creationTime);
              }
              return Optional.of(SignedMarkRevocationList.create(creationTime, revokes.build()));
            });
  }

  private static Optional<SignedMarkRevocationList> loadFromCloudSql() {
    return jpaTm()
        .transact(
            () -> {
              Long revisionId =
                  jpaTm()
                      .query("SELECT MAX(revisionId) FROM SignedMarkRevocationList", Long.class)
                      .getSingleResult();
              return jpaTm()
                  .query(
                      "FROM SignedMarkRevocationList smrl LEFT JOIN FETCH smrl.revokes "
                          + "WHERE smrl.revisionId = :revisionId",
                      SignedMarkRevocationList.class)
                  .setParameter("revisionId", revisionId)
                  .getResultStream()
                  .findFirst();
            });
  }

  /**
   * Save the given {@link SignedMarkRevocationList}
   *
   * <p>Saves the list to the specified primary database, and attempts to save to the secondary
   * database. If the save to the secondary database fails, the error will be logged but no
   * exception will be thrown.
   */
  static void save(SignedMarkRevocationList signedMarkRevocationList) {
    PrimaryDatabase primaryDatabase =
        tm().transactNew(
                () ->
                    DatabaseMigrationUtils.getPrimaryDatabase(
                        TransitionId.SIGNED_MARK_REVOCATION_LIST));
    if (primaryDatabase.equals(DATASTORE)) {
      saveToDatastore(signedMarkRevocationList.revokes, signedMarkRevocationList.creationTime);
      suppressExceptionUnlessInTest(
          () -> SignedMarkRevocationListDao.saveToCloudSql(signedMarkRevocationList),
          "Error inserting signed mark revocations into secondary database (Cloud SQL).");
    } else {
      SignedMarkRevocationListDao.saveToCloudSql(signedMarkRevocationList);
      suppressExceptionUnlessInTest(
          () ->
              saveToDatastore(
                  signedMarkRevocationList.revokes, signedMarkRevocationList.creationTime),
          "Error inserting signed mark revocations into secondary database (Datastore).");
    }
  }

  private static void saveToCloudSql(SignedMarkRevocationList signedMarkRevocationList) {
    jpaTm().transact(() -> jpaTm().insert(signedMarkRevocationList));
    logger.atInfo().log(
        "Inserted %,d signed mark revocations into Cloud SQL.",
        signedMarkRevocationList.revokes.size());
  }

  private static void saveToDatastore(Map<String, DateTime> revokes, DateTime creationTime) {
    tm().transact(
            () -> {
              ofy()
                  .deleteWithoutBackup()
                  .keys(
                      ofy()
                          .load()
                          .type(SignedMarkRevocationList.class)
                          .ancestor(getCrossTldKey())
                          .keys());
              ofy()
                  .saveWithoutBackup()
                  .entities(
                      CollectionUtils.partitionMap(revokes, SHARD_SIZE).stream()
                          .map(
                              shardRevokes -> {
                                SignedMarkRevocationList shard =
                                    SignedMarkRevocationList.create(creationTime, shardRevokes);
                                shard.id = allocateId();
                                shard.isShard =
                                    true; // Avoid the exception in disallowUnshardedSaves().
                                return shard;
                              })
                          .collect(toImmutableList()));
            });
  }
}
