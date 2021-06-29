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
import static google.registry.backup.BackupUtils.GcsMetadataKeys.LOWER_BOUND_CHECKPOINT;
import static google.registry.backup.ExportCommitLogDiffAction.DIFF_FILE_PREFIX;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;
import static google.registry.util.DateTimeUtils.latestOf;

import com.google.appengine.tools.cloudstorage.GcsFileMetadata;
import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.ListItem;
import com.google.appengine.tools.cloudstorage.ListOptions;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import google.registry.backup.BackupModule.Backups;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;
import javax.inject.Inject;
import org.joda.time.DateTime;

/** Utility class to list commit logs diff files stored on GCS. */
class GcsDiffFileLister {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject GcsService gcsService;
  @Inject @Backups ListeningExecutorService executor;
  @Inject GcsDiffFileLister() {}

  /**
   * Traverses the sequence of diff files backwards from checkpointTime and inserts the file
   * metadata into "sequence". Returns true if a complete sequence was discovered, false if one or
   * more files are missing.
   */
  private boolean constructDiffSequence(
      String gcsBucket,
      Map<DateTime, ListenableFuture<GcsFileMetadata>> upperBoundTimesToMetadata,
      DateTime fromTime,
      DateTime lastTime,
      TreeMap<DateTime, GcsFileMetadata> sequence) {
    DateTime checkpointTime = lastTime;
    while (isBeforeOrAt(fromTime, checkpointTime)) {
      GcsFileMetadata metadata;
      if (upperBoundTimesToMetadata.containsKey(checkpointTime)) {
        metadata = Futures.getUnchecked(upperBoundTimesToMetadata.get(checkpointTime));
      } else {
        String filename = DIFF_FILE_PREFIX + checkpointTime;
        logger.atInfo().log("Patching GCS list; discovered file: %s", filename);
        metadata = getMetadata(gcsBucket, filename);

        // If we hit a gap, quit.
        if (metadata == null) {
          logger.atInfo().log(
              "Gap discovered in sequence terminating at %s, missing file: %s",
              sequence.lastKey(), filename);
          logger.atInfo().log("Found sequence from %s to %s", checkpointTime, lastTime);
          return false;
        }
      }
      sequence.put(checkpointTime, metadata);
      checkpointTime = getLowerBoundTime(metadata);
    }
    logger.atInfo().log("Found sequence from %s to %s", checkpointTime, lastTime);
    return true;
  }

  ImmutableList<GcsFileMetadata> listDiffFiles(
      String gcsBucket, DateTime fromTime, @Nullable DateTime toTime) {
    logger.atInfo().log("Requested restore from time: %s", fromTime);
    if (toTime != null) {
      logger.atInfo().log("  Until time: %s", toTime);
    }
    // List all of the diff files on GCS and build a map from each file's upper checkpoint time
    // (extracted from the filename) to its asynchronously-loaded metadata, keeping only files with
    // an upper checkpoint time > fromTime.
    TreeMap<DateTime, ListenableFuture<GcsFileMetadata>> upperBoundTimesToMetadata
        = new TreeMap<>();
    Iterator<ListItem> listItems;
    try {
      // TODO(b/23554360): Use a smarter prefixing strategy to speed this up.
      listItems = gcsService.list(
          gcsBucket,
          new ListOptions.Builder().setPrefix(DIFF_FILE_PREFIX).build());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    DateTime lastUpperBoundTime = START_OF_TIME;
    while (listItems.hasNext()) {
      final String filename = listItems.next().getName();
      DateTime upperBoundTime = DateTime.parse(filename.substring(DIFF_FILE_PREFIX.length()));
      if (isInRange(upperBoundTime, fromTime, toTime)) {
        upperBoundTimesToMetadata.put(
            upperBoundTime, executor.submit(() -> getMetadata(gcsBucket, filename)));
        lastUpperBoundTime = latestOf(upperBoundTime, lastUpperBoundTime);
      }
    }
    if (upperBoundTimesToMetadata.isEmpty()) {
      logger.atInfo().log("No files found");
      return ImmutableList.of();
    }

    // Reconstruct the sequence of files by traversing backwards from "lastUpperBoundTime" (i.e. the
    // last file that we found) and finding its previous file until we either run out of files or
    // get to one that precedes "fromTime".
    //
    // GCS file listing is eventually consistent, so it's possible that we are missing a file. The
    // metadata of a file is sufficient to identify the preceding file, so if we start from the
    // last file and work backwards we can verify that we have no holes in our chain (although we
    // may be missing files at the end).
    TreeMap<DateTime, GcsFileMetadata> sequence = new TreeMap<>();
    logger.atInfo().log("Restoring until: %s", lastUpperBoundTime);
    boolean inconsistentFileSet =
        !constructDiffSequence(
            gcsBucket, upperBoundTimesToMetadata, fromTime, lastUpperBoundTime, sequence);

    // Verify that all of the elements in the original set are represented in the sequence.  If we
    // find anything that's not represented, construct a sequence for it.
    boolean checkForMoreExtraDiffs = true;  // Always loop at least once.
    while (checkForMoreExtraDiffs) {
      checkForMoreExtraDiffs = false;
      for (DateTime key : upperBoundTimesToMetadata.descendingKeySet()) {
        if (!isInRange(key, fromTime, toTime)) {
          break;
        }
        if (!sequence.containsKey(key)) {
          constructDiffSequence(gcsBucket, upperBoundTimesToMetadata, fromTime, key, sequence);
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

    logger.atInfo().log(
        "Actual restore from time: %s", getLowerBoundTime(sequence.firstEntry().getValue()));
    logger.atInfo().log("Found %d files to restore", sequence.size());
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

  private DateTime getLowerBoundTime(GcsFileMetadata metadata) {
    return DateTime.parse(metadata.getOptions().getUserMetadata().get(LOWER_BOUND_CHECKPOINT));
  }

  private GcsFileMetadata getMetadata(String gcsBucket, String filename) {
    try {
      return gcsService.getMetadata(new GcsFilename(gcsBucket, filename));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
