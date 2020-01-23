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
import google.registry.util.CollectionUtils;
import java.util.Map;
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
 * @see google.registry.tmch.SmdrlCsvParser
 * @see <a href="http://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-6.2">
 *     TMCH functional specifications - SMD Revocation List</a>
 */
@Entity
@NotBackedUp(reason = Reason.EXTERNALLY_SOURCED)
public class SignedMarkRevocationList extends ImmutableObject {

  @VisibleForTesting
  static final int SHARD_SIZE = 10000;

  /** Common ancestor for queries. */
  @Parent
  Key<EntityGroupRoot> parent = getCrossTldKey();

  /** ID for the sharded entity. */
  @Id
  long id;

  /** Time when this list was last updated, as specified in the first line of the CSV file. */
  DateTime creationTime;

  /** A map from SMD IDs to revocation time. */
  @EmbedMap
  Map</*@MatchesPattern("[0-9]+-[0-9]+")*/ String, DateTime> revokes;

  /** Indicates that this is a shard rather than a "full" list. */
  @Ignore
  boolean isShard;

  /**
   * A cached supplier that fetches the SMDRL shards from Datastore and recombines them into a
   * single {@link SignedMarkRevocationList} object.
   */
  private static final Supplier<SignedMarkRevocationList> CACHE =
      memoizeWithShortExpiration(
          () ->
              tm()
                  .transactNewReadOnly(
                      () -> {
                        Iterable<SignedMarkRevocationList> shards =
                            ofy()
                                .load()
                                .type(SignedMarkRevocationList.class)
                                .ancestor(getCrossTldKey());
                        DateTime creationTime =
                            isEmpty(shards)
                                ? START_OF_TIME
                                : checkNotNull(
                                    Iterables.get(shards, 0).creationTime, "creationTime");
                        ImmutableMap.Builder<String, DateTime> revokes =
                            new ImmutableMap.Builder<>();
                        for (SignedMarkRevocationList shard : shards) {
                          revokes.putAll(shard.revokes);
                          checkState(
                              creationTime.equals(shard.creationTime),
                              "Inconsistent creation times: %s vs. %s",
                              creationTime,
                              shard.creationTime);
                        }
                        return create(creationTime, revokes.build());
                      }));

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

  /** Save this list to Datastore in sharded form. Returns {@code this}. */
  public SignedMarkRevocationList save() {
    tm()
        .transact(
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
                      CollectionUtils.partitionMap(revokes, SHARD_SIZE)
                          .stream()
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
