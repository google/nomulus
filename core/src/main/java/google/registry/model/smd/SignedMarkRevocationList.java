// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.CacheUtils.memoizeWithShortExpiration;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.allocateId;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import com.google.common.flogger.FluentLogger;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.EmbedMap;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Ignore;
import com.googlecode.objectify.annotation.OnSave;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.NotBackedUp;
import google.registry.model.annotations.NotBackedUp.Reason;
import google.registry.model.common.EntityGroupRoot;
import google.registry.schema.replay.DuallyWrittenEntity;
import google.registry.util.CollectionUtils;
import java.util.Map;
import java.util.Optional;
import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.JoinColumn;
import javax.persistence.MapKeyColumn;
import javax.persistence.Transient;
import org.joda.time.DateTime;

/**
 * Signed Mark Data Revocation List (SMDRL).
 *
 * <p>Represents a SMDRL file downloaded from the TMCH MarksDB each day. The list holds the ids of
 * all the {@link SignedMark SignedMarks} that have been revoked. A new list is created for each new
 * file that's created, depending on the timestamp.
 *
 * <p>We'll be putting the entire table into a single entity for the sake of performance. But in
 * order to avoid exceeding the one megabyte max entity size limit, we'll also be sharding that
 * entity into multiple entities, each entity containing {@value #SHARD_SIZE} rows.
 *
 * <p>TODO: We can remove the sharding once we have converted entirely to Cloud SQL storage during
 * the Registry 3.0 migration. Then, the entire table will be stored conceptually as one entity (in
 * fact in SignedMarkRevocationList and SignedMarkRevocationEntry tables).
 *
 * @see google.registry.tmch.SmdrlCsvParser
 * @see <a href="http://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-6.2">TMCH
 *     functional specifications - SMD Revocation List</a>
 */
@Entity
@javax.persistence.Entity
@NotBackedUp(reason = Reason.EXTERNALLY_SOURCED)
public class SignedMarkRevocationList extends ImmutableObject implements DuallyWrittenEntity {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @VisibleForTesting static final int SHARD_SIZE = 10000;

  /** Common ancestor for queries. */
  @Parent @Transient Key<EntityGroupRoot> parent = getCrossTldKey();

  /** ID for the sharded entity. */
  @Id @Transient long id;

  @Ignore
  @javax.persistence.Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long revisionId;

  /** Time when this list was last updated, as specified in the first line of the CSV file. */
  DateTime creationTime;

  /** A map from SMD IDs to revocation time. */
  @EmbedMap
  @ElementCollection
  @CollectionTable(
      name = "SignedMarkRevocationEntry",
      joinColumns = @JoinColumn(name = "revisionId", referencedColumnName = "revisionId"))
  @MapKeyColumn(name = "smdId")
  @Column(name = "revocationTime", nullable = false)
  Map</*@MatchesPattern("[0-9]+-[0-9]+")*/ String, DateTime> revokes;

  /** Indicates that this is a shard rather than a "full" list. */
  @Ignore @Transient boolean isShard;

  /**
   * A cached supplier that fetches the SMDRL shards from Datastore and recombines them into a
   * single {@link SignedMarkRevocationList} object.
   */
  private static final Supplier<SignedMarkRevocationList> CACHE =
      memoizeWithShortExpiration(
          () -> {
            SignedMarkRevocationList datastoreList = loadFromDatastore();
            // Also load the list from Cloud SQL, compare the two lists, and log if different.
            try {
              loadAndCompareCloudSqlList(datastoreList);
            } catch (Throwable t) {
              logger.atSevere().withCause(t).log("Error comparing signed mark revocation lists.");
            }
            return datastoreList;
          });

  /** Return a single logical instance that combines all Datastore shards. */
  public static SignedMarkRevocationList get() {
    return CACHE.get();
  }

  /** Create a new {@link SignedMarkRevocationList} without saving it. */
  public static SignedMarkRevocationList create(
      DateTime creationTime, ImmutableMap<String, DateTime> revokes) {
    SignedMarkRevocationList instance = new SignedMarkRevocationList();
    instance.creationTime = checkNotNull(creationTime, "creationTime");
    instance.revokes = checkNotNull(revokes, "revokes");
    return instance;
  }

  /** Returns {@code true} if the SMD ID has been revoked at the given point in time. */
  public boolean isSmdRevoked(String smdId, DateTime now) {
    DateTime revoked = revokes.get(checkNotNull(smdId, "smdId"));
    return revoked != null && isBeforeOrAt(revoked, now);
  }

  /** Returns the creation timestamp specified at the top of the SMDRL CSV file. */
  public DateTime getCreationTime() {
    return creationTime;
  }

  /** Returns the number of revocations. */
  public int size() {
    return revokes.size();
  }

  /** Save this list to Datastore in sharded form and to Cloud SQL. Returns {@code this}. */
  public SignedMarkRevocationList save() {
    saveToDatastore();
    SignedMarkRevocationListDao.trySave(this);
    return this;
  }

  /** Loads the shards from Datastore and combines them into one list. */
  private static SignedMarkRevocationList loadFromDatastore() {
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
                    "Inconsistent creation times: %s vs. %s",
                    creationTime,
                    shard.creationTime);
              }
              return create(creationTime, revokes.build());
            });
  }

  /** Save this list to Datastore in sharded form. */
  private SignedMarkRevocationList saveToDatastore() {
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
                                SignedMarkRevocationList shard = create(creationTime, shardRevokes);
                                shard.id = allocateId();
                                shard.isShard =
                                    true; // Avoid the exception in disallowUnshardedSaves().
                                return shard;
                              })
                          .collect(toImmutableList()));
            });
    return this;
  }

  private static void loadAndCompareCloudSqlList(SignedMarkRevocationList datastoreList) {
    // Lifted with some modifications from ClaimsListShard
    Optional<SignedMarkRevocationList> maybeCloudSqlList =
        SignedMarkRevocationListDao.getLatestRevision();
    if (maybeCloudSqlList.isPresent()) {
      SignedMarkRevocationList cloudSqlList = maybeCloudSqlList.get();
      MapDifference<String, DateTime> diff =
          Maps.difference(datastoreList.revokes, cloudSqlList.revokes);
      if (!diff.areEqual()) {
        if (diff.entriesDiffering().size() > 10) {
          logger.atWarning().log(
              String.format(
                  "Unequal SM revocation lists detected, Cloud SQL list with revision id %d has %d"
                      + " different records than the current Datastore list.",
                  cloudSqlList.revisionId, diff.entriesDiffering().size()));
        } else {
          StringBuilder diffMessage = new StringBuilder("Unequal SM revocation lists detected:\n");
          diff.entriesDiffering()
              .forEach(
                  (label, valueDiff) ->
                      diffMessage.append(
                          String.format(
                              "SMD %s has key %s in Datastore and key %s in Cloud SQL.\n",
                              label, valueDiff.leftValue(), valueDiff.rightValue())));
          logger.atWarning().log(diffMessage.toString());
        }
      }
    } else {
      logger.atWarning().log("Signed mark revocation list in Cloud SQL is empty.");
    }
  }

  /** As a safety mechanism, fail if someone tries to save this class directly. */
  @OnSave
  void disallowUnshardedSaves() {
    if (!isShard) {
      throw new UnshardedSaveException();
    }
  }

  /** Exception when trying to directly save a {@link SignedMarkRevocationList} without sharding. */
  public static class UnshardedSaveException extends RuntimeException {}
}
