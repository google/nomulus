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
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.NotBackedUp;
import google.registry.model.annotations.NotBackedUp.Reason;
import google.registry.schema.replay.DatastoreEntity;
import google.registry.schema.replay.SqlEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.joda.time.DateTime;

/**
 * Entity representing a point-in-time consistent view of Datastore, based on commit logs.
 *
 * <p>Conceptually, this entity consists of two pieces of information: the checkpoint "wall" time
 * and a set of bucket checkpoint times. The former is the ID for this checkpoint (constrained to be
 * unique upon checkpoint creation) and also represents the approximate wall time of the consistent
 * Datastore view this checkpoint represents. The latter is really a mapping from bucket ID to
 * timestamp, where the timestamp dictates the upper bound (inclusive) on commit logs from that
 * bucket to include when restoring Datastore to this checkpoint.
 */
@Entity
@NotBackedUp(reason = Reason.COMMIT_LOGS)
public class CommitLogCheckpoint extends ImmutableObject implements DatastoreEntity {

  /** Shared singleton parent entity for commit log checkpoints. */
  @Parent
  Key<CommitLogCheckpointRoot> parent = CommitLogCheckpointRoot.getKey();

  /** The checkpoint's approximate "wall" time (in millis since the epoch). */
  @Id
  long checkpointTime;

  /** Bucket checkpoint times for this checkpoint, ordered to match up with buckets 1-N. */
  List<DateTime> bucketTimestamps = new ArrayList<>();

  public DateTime getCheckpointTime() {
    return new DateTime(checkpointTime, UTC);
  }

  /** Returns the bucket checkpoint times as a map from bucket ID to commit timestamp. */
  public ImmutableMap<Integer, DateTime> getBucketTimestamps() {
    ImmutableMap.Builder<Integer, DateTime> builder = new ImmutableMap.Builder<>();
    for (int i = 0; i < bucketTimestamps.size(); ++i) {
      // Add 1 to map the bucket timestamps properly to buckets indexed from 1-N.
      builder.put(i + 1, bucketTimestamps.get(i));
    }
    return builder.build();
  }

  @Override
  public ImmutableList<SqlEntity> toSqlEntities() {
    return ImmutableList.of(); // not persisted in SQL
  }

  /**
   * Creates a CommitLogCheckpoint for the given wall time and bucket checkpoint times, specified as
   * a map from bucket ID to bucket commit timestamp.
   */
  public static CommitLogCheckpoint create(
      DateTime checkpointTime, ImmutableMap<Integer, DateTime> bucketTimestamps) {
    checkArgument(
        Objects.equals(CommitLogBucket.getBucketIds().asList(), bucketTimestamps.keySet().asList()),
        "Bucket ids are incorrect: %s",
        bucketTimestamps.keySet());
    CommitLogCheckpoint instance = new CommitLogCheckpoint();
    instance.checkpointTime = checkpointTime.getMillis();
    instance.bucketTimestamps = ImmutableList.copyOf(bucketTimestamps.values());
    return instance;
  }

  /**
   * Creates a CommitLogCheckpoint for the given wall time and bucket checkpoint times. Test only.
   *
   * <p>This lacks validation on the bucketTimestamps map.
   */
  @VisibleForTesting
  public static CommitLogCheckpoint createForTest(
      DateTime checkpointTime, ImmutableMap<Integer, DateTime> bucketTimestamps) {
    CommitLogCheckpoint instance = new CommitLogCheckpoint();
    instance.checkpointTime = checkpointTime.getMillis();
    instance.bucketTimestamps = ImmutableList.copyOf(bucketTimestamps.values());
    return instance;
  }

  /** Creates a key for the CommitLogCheckpoint for the given wall time. */
  public static Key<CommitLogCheckpoint> createKey(DateTime checkpointTime) {
    return Key.create(
        CommitLogCheckpointRoot.getKey(), CommitLogCheckpoint.class, checkpointTime.getMillis());
  }
}
