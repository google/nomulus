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

package google.registry.model.ofy;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.DiscreteDomain.integers;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.config.RegistryConfig.getCommitLogBucketCount;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ContiguousSet;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Range;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import google.registry.config.RegistryConfig;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.NotBackedUp;
import google.registry.model.annotations.NotBackedUp.Reason;
import google.registry.schema.replay.DatastoreOnlyEntity;
import google.registry.util.NonFinalForTesting;
import java.util.Random;
import java.util.function.Supplier;
import org.joda.time.DateTime;

/**
 * Root for a random commit log bucket.
 *
 * <p>This is used to shard {@link CommitLogManifest} objects into {@link
 * RegistryConfig#getCommitLogBucketCount() N} entity groups. This increases transaction throughput,
 * while maintaining the ability to perform strongly-consistent ancestor queries.
 *
 * @see <a href="https://cloud.google.com/appengine/articles/scaling/contention">Avoiding Datastore
 *     contention</a>
 */
@Entity
@NotBackedUp(reason = Reason.COMMIT_LOGS)
public class CommitLogBucket extends ImmutableObject implements Buildable, DatastoreOnlyEntity {

  /**
   * Ranges from 1 to {@link RegistryConfig#getCommitLogBucketCount()}, inclusive; starts at 1 since
   * IDs can't be 0.
   */
  @Id long bucketNum;

  /** The timestamp of the last {@link CommitLogManifest} written to this bucket. */
  DateTime lastWrittenTime = START_OF_TIME;

  public int getBucketNum() {
    return (int) bucketNum;
  }

  public DateTime getLastWrittenTime() {
    return lastWrittenTime;
  }

  /**
   * Returns the key for the specified bucket ID.
   *
   * <p>Always use this method in preference to manually creating bucket keys, since manual keys
   * are not guaranteed to have a valid bucket ID number.
   */
  public static Key<CommitLogBucket> getBucketKey(int num) {
    checkArgument(getBucketIdRange().contains(num), "%s not in %s", num, getBucketIdRange());
    return getBucketKeyUnsafe(num);
  }

  private static Key<CommitLogBucket> getBucketKeyUnsafe(int num) {
    return Key.create(CommitLogBucket.class, num);
  }

  /** Returns a sorted set of all the possible numeric bucket IDs. */
  public static ImmutableSortedSet<Integer> getBucketIds() {
    return ContiguousSet.create(getBucketIdRange(), integers());
  }

  private static Range<Integer> getBucketIdRange() {
    return Range.closed(1, getCommitLogBucketCount());
  }

  /** Returns an arbitrary numeric bucket ID.  Default behavior is randomly chosen IDs. */
  public static int getArbitraryBucketId() {
    return bucketIdSupplier.get();
  }

  /**
   * Supplier of valid bucket IDs to use for {@link #getArbitraryBucketId()}.
   *
   * <p>Default supplier is one that returns bucket IDs via uniform random selection, but can be
   * overridden in tests that rely on predictable bucket assignment for commit logs.
   */
  @NonFinalForTesting
  private static Supplier<Integer> bucketIdSupplier =
      new Supplier<Integer>() {
        private final Random random = new Random();

        @Override
        public Integer get() {
          return random.nextInt(getCommitLogBucketCount()) + 1;  // Add 1 since IDs can't be 0.
        }
      };

  /** Returns the loaded bucket for the given key, or a new object if the bucket doesn't exist. */
  public static CommitLogBucket loadBucket(Key<CommitLogBucket> bucketKey) {
    CommitLogBucket bucket = auditedOfy().load().key(bucketKey).now();
    return (bucket == null)
        ? new CommitLogBucket.Builder().setBucketNum(bucketKey.getId()).build()
        : bucket;
  }

  /** Returns the set of all loaded commit log buckets, filling in missing buckets with new ones. */
  public static ImmutableSet<CommitLogBucket> loadAllBuckets() {
    auditedOfy().load().keys(getAllBucketKeys()); // Load all buckets into session cache at once.
    ImmutableSet.Builder<CommitLogBucket> allBuckets = new ImmutableSet.Builder<>();
    for (Key<CommitLogBucket> key : getAllBucketKeys()) {
      allBuckets.add(loadBucket(key));
    }
    return allBuckets.build();
  }

  /** Returns all commit log bucket keys, in ascending order by bucket ID. */
  public static ImmutableSet<Key<CommitLogBucket>> getAllBucketKeys() {
    return getBucketIds()
        .stream()
        .map(CommitLogBucket::getBucketKeyUnsafe)
        .collect(toImmutableSet());
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

   /** A builder for {@link CommitLogBucket} since it is immutable. */
  public static class Builder extends Buildable.Builder<CommitLogBucket> {
    public Builder() {}

    public Builder(CommitLogBucket instance) {
      super(instance);
    }

    public Builder setBucketNum(long bucketNum) {
      getInstance().bucketNum = bucketNum;
      return this;
    }

    public Builder setLastWrittenTime(DateTime lastWrittenTime) {
      getInstance().lastWrittenTime = lastWrittenTime;
      return this;
    }
  }
}
