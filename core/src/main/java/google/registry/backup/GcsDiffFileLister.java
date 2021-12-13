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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.backup.BackupUtils.GcsMetadataKeys.LOWER_BOUND_CHECKPOINT;
import static google.registry.backup.ExportCommitLogDiffAction.DIFF_FILE_PREFIX;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;
import static google.registry.util.DateTimeUtils.latestOf;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.UncheckedExecutionException;
import google.registry.backup.BackupModule.Backups;
import google.registry.gcs.GcsUtils;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import org.joda.time.DateTime;

/** Utility class to list commit logs diff files stored on GCS. */
class GcsDiffFileLister {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Timeout for retrieving per-file information from GCS. */
  private static final Duration FILE_INFO_TIMEOUT_DURATION = Duration.ofMinutes(1);

  @Inject GcsUtils gcsUtils;

  @Inject @Backups Provider<ListeningExecutorService> executorProvider;
  @Inject ScheduledExecutorService scheduledExecutorService;

  @Inject
  GcsDiffFileLister() {}

  /**
   * Traverses the sequence of diff files backwards from checkpointTime and inserts the file
   * metadata into "sequence". Returns true if a complete sequence was discovered, false if one or
   * more files are missing.
   *
   * @throws UncheckedExecutionException wrapping a {@link java.util.concurrent.TimeoutException} if
   *     the GCS call fails to finish within one minute, or wrapping any other exception if
   *     something else goes wrong.
   */
  private boolean constructDiffSequence(
      String gcsBucket,
      Map<DateTime, ListenableFuture<BlobInfo>> upperBoundTimesToBlobInfo,
      DateTime fromTime,
      DateTime lastTime,
      TreeMap<DateTime, BlobInfo> sequence) {
    DateTime checkpointTime = lastTime;
    while (isBeforeOrAt(fromTime, checkpointTime)) {
      BlobInfo blobInfo;
      if (upperBoundTimesToBlobInfo.containsKey(checkpointTime)) {
        blobInfo =
            Futures.getUnchecked(
                Futures.withTimeout(
                    upperBoundTimesToBlobInfo.get(checkpointTime),
                    FILE_INFO_TIMEOUT_DURATION,
                    scheduledExecutorService));
      } else {
        String filename = DIFF_FILE_PREFIX + checkpointTime;
        logger.atInfo().log("Patching GCS list; discovered file: %s", filename);
        blobInfo = getBlobInfo(gcsBucket, filename);

        // If we hit a gap, quit.
        if (blobInfo == null) {
          logger.atWarning().log(
              "Gap discovered in sequence terminating at %s, missing file: %s",
              sequence.lastKey(), filename);
          logger.atInfo().log("Found sequence from %s to %s.", checkpointTime, lastTime);
          return false;
        }
      }
      sequence.put(checkpointTime, blobInfo);
      checkpointTime = getLowerBoundTime(blobInfo);
    }
    logger.atInfo().log("Found sequence from %s to %s.", checkpointTime, lastTime);
    return true;
  }

  ImmutableList<BlobInfo> listDiffFiles(
      String gcsBucket, DateTime fromTime, @Nullable DateTime toTime) {
    logger.atInfo().log("Requested restore from time: %s", fromTime);
    if (toTime != null) {
      logger.atInfo().log("  Until time: %s", toTime);
    }
    // List all of the diff files on GCS and build a map from each file's upper checkpoint time
    // (extracted from the filename) to its asynchronously-loaded metadata, keeping only files with
    // an upper checkpoint time > fromTime.
    TreeMap<DateTime, ListenableFuture<BlobInfo>> upperBoundTimesToBlobInfo = new TreeMap<>();
    String commitLogDiffPrefix = getCommitLogDiffPrefix(fromTime, toTime);
    ImmutableList<String> filenames;
    try {
      filenames =
          gcsUtils.listFolderObjects(gcsBucket, commitLogDiffPrefix).stream()
              .map(s -> commitLogDiffPrefix + s)
              .collect(toImmutableList());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    DateTime lastUpperBoundTime = START_OF_TIME;

    TreeMap<DateTime, BlobInfo> sequence = new TreeMap<>();
    ListeningExecutorService executor = executorProvider.get();
    try {
      for (String filename : filenames) {
        String strippedFilename = filename.replaceFirst(DIFF_FILE_PREFIX, "");
        DateTime upperBoundTime = DateTime.parse(strippedFilename);
        if (isInRange(upperBoundTime, fromTime, toTime)) {
          upperBoundTimesToBlobInfo.put(
              upperBoundTime, executor.submit(() -> getBlobInfo(gcsBucket, filename)));
          lastUpperBoundTime = latestOf(upperBoundTime, lastUpperBoundTime);
        }
      }
      if (upperBoundTimesToBlobInfo.isEmpty()) {
        logger.atInfo().log("No files found.");
        return ImmutableList.of();
      }

      // Reconstruct the sequence of files by traversing backwards from "lastUpperBoundTime" (i.e.
      // the last file that we found) and finding its previous file until we either run out of files
      // or get to one that precedes "fromTime".
      //
      // GCS file listing is eventually consistent, so it's possible that we are missing a file. The
      // metadata of a file is sufficient to identify the preceding file, so if we start from the
      // last file and work backwards we can verify that we have no holes in our chain (although we
      // may be missing files at the end).
      logger.atInfo().log("Restoring until: %s", lastUpperBoundTime);
      boolean inconsistentFileSet =
          !constructDiffSequence(
              gcsBucket, upperBoundTimesToBlobInfo, fromTime, lastUpperBoundTime, sequence);

      // Verify that all of the elements in the original set are represented in the sequence.  If we
      // find anything that's not represented, construct a sequence for it.
      boolean checkForMoreExtraDiffs = true; // Always loop at least once.
      while (checkForMoreExtraDiffs) {
        checkForMoreExtraDiffs = false;
        for (DateTime key : upperBoundTimesToBlobInfo.descendingKeySet()) {
          if (!isInRange(key, fromTime, toTime)) {
            break;
          }
          if (!sequence.containsKey(key)) {
            // Recalculate the sequence for purely informational purposes.
            logger.atWarning().log(
                "Fork found in commit log history.  The following sequence "
                    + "is disconnected from the sequence of the final commit:");
            constructDiffSequence(gcsBucket, upperBoundTimesToBlobInfo, fromTime, key, sequence);
            checkForMoreExtraDiffs = true;
            inconsistentFileSet = true;
            break;
          }
        }
      }

      checkState(
          !inconsistentFileSet,
          "Unable to compute commit diff history, there are either gaps or forks in the history "
              + "file set.  Check log for details.");
    } finally {
      executor.shutdown();
    }

    logger.atInfo().log(
        "Actual restore from time: %s", getLowerBoundTime(sequence.firstEntry().getValue()));
    logger.atInfo().log("Found %d files to restore.", sequence.size());
    return ImmutableList.copyOf(sequence.values());
  }

  /**
   * Returns true if 'time' is in range of 'start' and 'end'.
   *
   * <p>If 'end' is null, returns true if 'time' is after 'start'.
   */
  private boolean isInRange(DateTime time, DateTime start, @Nullable DateTime end) {
    return isBeforeOrAt(start, time) && (end == null || isBeforeOrAt(time, end));
  }

  private DateTime getLowerBoundTime(BlobInfo blobInfo) {
    return DateTime.parse(blobInfo.getMetadata().get(LOWER_BOUND_CHECKPOINT));
  }

  private BlobInfo getBlobInfo(String gcsBucket, String filename) {
    return gcsUtils.getBlobInfo(BlobId.of(gcsBucket, filename));
  }

  /**
   * Returns a prefix guaranteed to cover all commit log diff files in the given range.
   *
   * <p>The listObjects call can be fairly slow if we search over many thousands or tens of
   * thousands of files, so we restrict the search space. The commit logs have a file format of
   * "commit_diff_until_2021-05-11T06:48:00.070Z" so we can often filter down as far as the hour.
   *
   * <p>Here, we get the longest prefix possible based on which fields (year, month, day, hour) the
   * times in question have in common.
   */
  @VisibleForTesting
  static String getCommitLogDiffPrefix(DateTime from, @Nullable DateTime to) {
    StringBuilder result = new StringBuilder(DIFF_FILE_PREFIX);
    if (to == null || from.getYear() != to.getYear()) {
      return result.toString();
    }
    result.append(from.getYear()).append('-');
    if (from.getMonthOfYear() != to.getMonthOfYear()) {
      return result.toString();
    }
    result.append(String.format("%02d-", from.getMonthOfYear()));
    if (from.getDayOfMonth() != to.getDayOfMonth()) {
      return result.toString();
    }
    result.append(String.format("%02dT", from.getDayOfMonth()));
    if (from.getHourOfDay() != to.getHourOfDay()) {
      return result.toString();
    }
    result.append(String.format("%02d:", from.getHourOfDay()));
    return result.toString();
  }
}
