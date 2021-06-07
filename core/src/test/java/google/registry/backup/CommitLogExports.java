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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Lists.partition;
import static google.registry.backup.BackupUtils.serializeEntity;
import static google.registry.model.ofy.CommitLogBucket.getBucketKey;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DateTimeUtils.isAtOrAfter;
import static java.util.Comparator.comparingLong;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.googlecode.objectify.Key;
import google.registry.model.ImmutableObject;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.ofy.CommitLogCheckpoint;
import google.registry.model.ofy.CommitLogCheckpointRoot;
import google.registry.model.ofy.CommitLogManifest;
import google.registry.model.ofy.CommitLogMutation;
import google.registry.util.Clock;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/**
 * Helpers for exporting the diff between two commit log checkpoints to a local file.
 *
 * <p>In production, CommitLogs are saved periodically by cron jobs. During each job, the {@link
 * CommitLogCheckpointAction} is invoked first to compute a {@link CommitLogCheckpoint} and persist
 * it in Datastore. Then the {@link ExportCommitLogDiffAction} is invoked to export the diffs
 * accumulated between the previous and current checkpoints to a file.
 *
 * <p>The {@link #computeCheckpoint(Clock)} method is copied with simplification from {@link
 * CommitLogCheckpointAction}, and the {@link #saveCommitLogs(String, CommitLogCheckpoint,
 * CommitLogCheckpoint)} method is copied with simplification from {@link
 * ExportCommitLogDiffAction}. We opted for copying instead of refactoring to reduce risk to
 * production code.
 */
public final class CommitLogExports {

  public static final String DIFF_FILE_PREFIX = "commit_diff_until_";

  private static final int EXPORT_DIFF_BATCH_SIZE = 100;

  private CommitLogExports() {}

  /**
   * Returns the next {@link CommitLogCheckpoint} for Commit logs. Please refer to the class javadoc
   * for background.
   */
  public static CommitLogCheckpoint computeCheckpoint(Clock clock) {
    CommitLogCheckpointStrategy strategy = new CommitLogCheckpointStrategy();
    strategy.clock = clock;
    strategy.ofy = auditedOfy();

    CommitLogCheckpoint checkpoint = strategy.computeCheckpoint();
    tm().transact(
            () -> {
              DateTime lastWrittenTime = CommitLogCheckpointRoot.loadRoot().getLastWrittenTime();
              checkState(
                  checkpoint.getCheckpointTime().isAfter(lastWrittenTime),
                  "Newer checkpoint already written at time: %s",
                  lastWrittenTime);
              auditedOfy()
                  .saveWithoutBackup()
                  .entities(
                      checkpoint, CommitLogCheckpointRoot.create(checkpoint.getCheckpointTime()));
            });
    return checkpoint;
  }

  /**
   * Saves the incremental changes between {@code prevCheckpoint} and {@code checkpoint} and returns
   * the {@link File}. Please refer to class javadoc for background.
   */
  public static File saveCommitLogs(
      String commitLogDir,
      @Nullable CommitLogCheckpoint prevCheckpoint,
      CommitLogCheckpoint checkpoint) {
    checkArgument(
        prevCheckpoint == null
            || (isAtOrAfter(prevCheckpoint.getCheckpointTime(), START_OF_TIME)
                && prevCheckpoint.getCheckpointTime().isBefore(checkpoint.getCheckpointTime())),
        "Inversed checkpoint: prev is %s, current is %s.",
        Optional.ofNullable(prevCheckpoint)
            .map(CommitLogCheckpoint::getCheckpointTime)
            .map(DateTime::toString)
            .orElse("null"),
        checkpoint.getCheckpointTime().toString());

    // Load the keys of all the manifests to include in this diff.
    List<Key<CommitLogManifest>> sortedKeys = loadAllDiffKeys(prevCheckpoint, checkpoint);
    // Open an output channel to GCS, wrapped in a stream for convenience.
    File commitLogFile =
        new File(commitLogDir + "/" + DIFF_FILE_PREFIX + checkpoint.getCheckpointTime());
    try (OutputStream commitLogStream =
        new BufferedOutputStream(new FileOutputStream(commitLogFile))) {
      // Export the upper checkpoint itself.
      serializeEntity(checkpoint, commitLogStream);
      // If there are no manifests to export, stop early, now that we've written out the file with
      // the checkpoint itself (which is needed for restores, even if it's empty).
      if (sortedKeys.isEmpty()) {
        return commitLogFile;
      }
      // Export to GCS in chunks, one per fixed batch of commit logs.  While processing one batch,
      // asynchronously load the entities for the next one.
      List<List<Key<CommitLogManifest>>> keyChunks = partition(sortedKeys, EXPORT_DIFF_BATCH_SIZE);
      // Objectify's map return type is asynchronous. Calling .values() will block until it loads.
      Map<?, CommitLogManifest> nextChunkToExport = auditedOfy().load().keys(keyChunks.get(0));
      for (int i = 0; i < keyChunks.size(); i++) {
        // Force the async load to finish.
        Collection<CommitLogManifest> chunkValues = nextChunkToExport.values();
        // Since there is no hard bound on how much data this might be, take care not to let the
        // Objectify session cache fill up and potentially run out of memory. This is the only safe
        // point to do this since at this point there is no async load in progress.
        auditedOfy().clearSessionCache();
        // Kick off the next async load, which can happen in parallel to the current GCS export.
        if (i + 1 < keyChunks.size()) {
          nextChunkToExport = auditedOfy().load().keys(keyChunks.get(i + 1));
        }
        exportChunk(commitLogStream, chunkValues);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return commitLogFile;
  }

  /**
   * Loads all the diff keys, sorted in a transaction-consistent chronological order.
   *
   * @param lowerCheckpoint exclusive lower bound on keys in this diff, or null if no lower bound
   * @param upperCheckpoint inclusive upper bound on keys in this diff
   */
  private static ImmutableList<Key<CommitLogManifest>> loadAllDiffKeys(
      @Nullable final CommitLogCheckpoint lowerCheckpoint,
      final CommitLogCheckpoint upperCheckpoint) {
    // Fetch the keys (no data) between these checkpoints, and sort by timestamp. This ordering is
    // transaction-consistent by virtue of our checkpoint strategy and our customized Ofy; see
    // CommitLogCheckpointStrategy for the proof. We break ties by sorting on bucket ID to ensure
    // a deterministic order.
    return upperCheckpoint.getBucketTimestamps().keySet().stream()
        .flatMap(
            bucketNum ->
                Streams.stream(loadDiffKeysFromBucket(lowerCheckpoint, upperCheckpoint, bucketNum)))
        .sorted(
            comparingLong(Key<CommitLogManifest>::getId)
                .thenComparingLong(a -> a.getParent().getId()))
        .collect(toImmutableList());
  }

  /**
   * Loads the diff keys for one bucket.
   *
   * @param lowerCheckpoint exclusive lower bound on keys in this diff, or null if no lower bound
   * @param upperCheckpoint inclusive upper bound on keys in this diff
   * @param bucketNum the bucket to load diff keys from
   */
  private static Iterable<Key<CommitLogManifest>> loadDiffKeysFromBucket(
      @Nullable CommitLogCheckpoint lowerCheckpoint,
      CommitLogCheckpoint upperCheckpoint,
      int bucketNum) {
    // If no lower checkpoint exists, or if it exists but had no timestamp for this bucket number
    // (because the bucket count was increased between these checkpoints), then use START_OF_TIME
    // as the effective exclusive lower bound.
    DateTime lowerCheckpointBucketTime =
        firstNonNull(
            (lowerCheckpoint == null) ? null : lowerCheckpoint.getBucketTimestamps().get(bucketNum),
            START_OF_TIME);
    // Since START_OF_TIME=0 is not a valid id in a key, add 1 to both bounds. Then instead of
    // loading lowerBound < x <= upperBound, we can load lowerBound <= x < upperBound.
    DateTime lowerBound = lowerCheckpointBucketTime.plusMillis(1);
    DateTime upperBound = upperCheckpoint.getBucketTimestamps().get(bucketNum).plusMillis(1);
    // If the lower and upper bounds are equal, there can't be any results, so skip the query.
    if (lowerBound.equals(upperBound)) {
      return ImmutableSet.of();
    }
    Key<CommitLogBucket> bucketKey = getBucketKey(bucketNum);
    return auditedOfy()
        .load()
        .type(CommitLogManifest.class)
        .ancestor(bucketKey)
        .filterKey(">=", CommitLogManifest.createKey(bucketKey, lowerBound))
        .filterKey("<", CommitLogManifest.createKey(bucketKey, upperBound))
        .keys();
  }

  /** Writes a chunks-worth of manifests and associated mutations to GCS. */
  private static void exportChunk(OutputStream gcsStream, Collection<CommitLogManifest> chunk)
      throws IOException {
    // Kickoff async loads for all the manifests in the chunk.
    ImmutableList.Builder<Iterable<? extends ImmutableObject>> entities =
        new ImmutableList.Builder<>();
    for (CommitLogManifest manifest : chunk) {
      entities.add(ImmutableList.of(manifest));
      entities.add(auditedOfy().load().type(CommitLogMutation.class).ancestor(manifest));
    }
    for (ImmutableObject entity : concat(entities.build())) {
      serializeEntity(entity, gcsStream);
    }
  }
}
