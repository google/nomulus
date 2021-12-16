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

package google.registry.backup;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.Maps.transformValues;
import static google.registry.model.ofy.CommitLogBucket.getBucketKey;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.earliestOf;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.Key;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.ofy.CommitLogCheckpoint;
import google.registry.model.ofy.CommitLogManifest;
import google.registry.model.ofy.Ofy;
import google.registry.util.Clock;
import java.util.List;
import java.util.Map.Entry;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * Implementation of the procedure for determining point-in-time consistent commit log checkpoint.
 *
 * <p>This algorithm examines the recently written commit log data and uses a dual-read approach to
 * determine a point-in-time consistent set of checkpoint times for the commit log buckets. By
 * "consistent" we mean, generally speaking, that if the Datastore were restored by replaying all
 * the commit logs up to the checkpoint times of the buckets, the result would be transactionally
 * correct; there must be no "holes" where restored state depends on non-restored state.
 *
 * <p>The consistency guarantee really has two parts, only one of which is provided by this
 * algorithm. The procedure below guarantees only that if the resulting checkpoint includes any
 * given commit log, it will also include all the commit logs that were both 1) actually written
 * before that commit log "in real life", and 2) have an earlier timestamp than that commit log.
 * (These criteria do not necessarily imply each other, due to the lack of a global shared clock.)
 * The rest of the guarantee comes from our Ofy customizations, which ensure that any transaction
 * that depends on state from a previous transaction does indeed have a later timestamp.
 *
 * <h2>Procedure description</h2>
 *
 * <pre>{@code
 * ComputeCheckpoint() -> returns a set consisting of a timestamp c(b_i) for every bucket b_i
 *
 * 1) read off the latest commit timestamp t(b_i) for every bucket b_i
 * 2) iterate over the buckets b_i a second time, and
 *   a) do a consistent query for the next commit timestamp t'(b_i) where t'(b_i) > t(b_i)
 *   b) if present, add this timestamp t'(b_i) to a set S
 * 3) compute a threshold time T* representing a time before all commits in S, as follows:
 *   a) if S is empty, let T* = +∞ (or the "end of time")
 *   b) else, let T* = T - Δ, for T = min(S) and some small Δ > 0
 * 4) return the set given by: min(t(b_i), T*) for all b_i
 * }</pre>
 *
 * <h2>Correctness proof of algorithm</h2>
 *
 * <p>{@literal As described above, the algorithm is correct as long as it can ensure the following:
 * given a commit log X written at time t(X) to bucket b_x, and another commit log Y that was
 * written "in real life" before X and for which t(Y) < t(X), then if X is included in the
 * checkpoint, so is Y; that is, t(X) <= c(b_x) implies t(Y) <= c(b_y). }
 *
 * <p>{@literal To prove this, first note that we always have c(b_i) <= t(b_i) for every b_i, i.e.
 * every commit log included in the checkpoint must have been seen in the first pass. Hence if X was
 * included, then X must have been written by the time we started the second pass. But since Y was
 * written "in real life" prior to X, we must have seen Y by the second pass too. }
 *
 * <p>{@literal Now assume towards a contradiction that X is indeed included but Y is not, i.e. that
 * we have t(X) <= c(b_x) but t(Y) > c(b_y). If Y was seen in the first pass, i.e. t(Y) <= t(b_y),
 * then by our assumption c(b_y) < t(Y) <= t(b_y), and therefore c(b_y) != t(b_y). By the definition
 * of c(b_y) it must then equal T*, so we have T* < t(Y). However, this is a contradiction since
 * t(Y) < t(X) and t(X) <= c(b_x) <= T*. If instead Y was seen in the second pass but not the first,
 * t'(b_y) exists and we must have t'(b_y) <= t(Y), but then since T* < T <= t'(b_y) by definition,
 * we again reach the contradiction T* < t(Y). }
 */
@DeleteAfterMigration
class CommitLogCheckpointStrategy {

  @Inject Ofy ofy;
  @Inject Clock clock;
  @Inject CommitLogCheckpointStrategy() {}

  /** Compute and return a new CommitLogCheckpoint for the current point in time. */
  public CommitLogCheckpoint computeCheckpoint() {
    DateTime checkpointTime = clock.nowUtc();
    ImmutableMap<Integer, DateTime> firstPassTimes = readBucketTimestamps();
    DateTime threshold = readNewCommitLogsAndFindThreshold(firstPassTimes);
    return CommitLogCheckpoint.create(
        checkpointTime,
        computeBucketCheckpointTimes(firstPassTimes, threshold));
  }

  /**
   * Returns a map from all bucket IDs to their current last written time values, fetched without
   * a transaction so with no guarantee of consistency across buckets.
   */
  @VisibleForTesting
  ImmutableMap<Integer, DateTime> readBucketTimestamps() {
    // Use a fresh session cache so that we get the latest data from Datastore.
    return ofy.doWithFreshSessionCache(
        () ->
            CommitLogBucket.loadAllBuckets()
                .stream()
                .collect(
                    ImmutableMap.toImmutableMap(
                        CommitLogBucket::getBucketNum, CommitLogBucket::getLastWrittenTime)));
  }

  /**
   * Returns a threshold value defined as the latest timestamp that is before all new commit logs,
   * where "new" means having a commit time after the per-bucket timestamp in the given map.
   * When no such commit logs exist, the threshold value is set to END_OF_TIME.
   */
  @VisibleForTesting
  DateTime readNewCommitLogsAndFindThreshold(ImmutableMap<Integer, DateTime> bucketTimes) {
    DateTime timeBeforeAllNewCommits = END_OF_TIME;
    for (Entry<Integer, DateTime> entry : bucketTimes.entrySet()) {
      Key<CommitLogBucket> bucketKey = getBucketKey(entry.getKey());
      DateTime bucketTime = entry.getValue();
      // Add 1 to handle START_OF_TIME since 0 isn't a valid id - filter then uses >= instead of >.
      Key<CommitLogManifest> keyForFilter =
          Key.create(CommitLogManifest.create(bucketKey, bucketTime.plusMillis(1), null));
      List<Key<CommitLogManifest>> manifestKeys =
          ofy.load()
              .type(CommitLogManifest.class)
              .ancestor(bucketKey)
              .filterKey(">=", keyForFilter)
              .limit(1)
              .keys()
              .list();
      if (!manifestKeys.isEmpty()) {
        timeBeforeAllNewCommits = earliestOf(
            timeBeforeAllNewCommits,
            CommitLogManifest.extractCommitTime(getOnlyElement(manifestKeys)).minusMillis(1));
      }
    }
    return timeBeforeAllNewCommits;
  }

  /**
   * Returns the bucket checkpoint times produced by clamping the given set of bucket timestamps to
   * at most the given threshold value.
   */
  @VisibleForTesting
  ImmutableMap<Integer, DateTime> computeBucketCheckpointTimes(
      ImmutableMap<Integer, DateTime> firstPassTimes,
      final DateTime threshold) {
    return ImmutableMap.copyOf(
        transformValues(firstPassTimes, firstPassTime -> earliestOf(firstPassTime, threshold)));
  }
}
