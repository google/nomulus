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

package google.registry.beam.initsql;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.beam.initsql.BackupPaths.getCommitLogFileNamePattern;
import static google.registry.beam.initsql.BackupPaths.getCommitLogTimestamp;
import static google.registry.beam.initsql.Transforms.processFiles;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;

import google.registry.backup.CommitLogImports;
import google.registry.backup.VersionedEntity;
import java.io.IOException;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.FileIO.ReadableFile;
import org.apache.beam.sdk.io.fs.MatchResult.Metadata;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.DateTime;

/**
 * {@link org.apache.beam.sdk.transforms.PTransform Pipeline transforms} for loading from Nomulus
 * CommitLog files. They are all part of a transformation that loads raw records from a sequence of
 * Datastore CommitLog files, and are broken apart for testing.
 */
public class CommitLogTransforms {

  /**
   * Returns a {@link PTransform transform} that can generate a collection of patterns that match
   * all Datastore CommitLog files.
   */
  public static PTransform<PBegin, PCollection<String>> getCommitLogFilePatterns(
      String commitLogDir) {
    return Create.of(getCommitLogFileNamePattern(commitLogDir)).withCoder(StringUtf8Coder.of());
  }

  /**
   * Returns files with timestamps between {@code fromTime} (inclusive) and {@code endTime}
   * (exclusive).
   */
  public static PTransform<PCollection<? extends String>, PCollection<String>>
      filterCommitLogsByTime(DateTime fromTime, DateTime toTime) {
    checkNotNull(fromTime, "fromTime");
    checkNotNull(toTime, "toTime");
    checkArgument(
        fromTime.isBefore(toTime),
        "Invalid time range: fromTime (%s) is before endTime (%s)",
        fromTime,
        toTime);
    return ParDo.of(new FilterCommitLogFileByTime(fromTime, toTime));
  }

  /** Returns a {@link PTransform} from file {@link Metadata} to {@link VersionedEntity}. */
  public static PTransform<PCollection<Metadata>, PCollection<VersionedEntity>>
      loadCommitLogsFromFiles() {
    return processFiles(new LoadOneCommitLogsFile());
  }

  static class FilterCommitLogFileByTime extends DoFn<String, String> {
    private final DateTime fromTime;
    private final DateTime toTime;

    public FilterCommitLogFileByTime(DateTime fromTime, DateTime toTime) {
      this.fromTime = fromTime;
      this.toTime = toTime;
    }

    @ProcessElement
    public void processElement(@Element String fileName, OutputReceiver<String> out) {
      DateTime timestamp = getCommitLogTimestamp(fileName);
      if (isBeforeOrAt(fromTime, timestamp) && timestamp.isBefore(toTime)) {
        out.output(fileName);
      }
    }
  }

  /**
   * Reads a CommitLog file and converts its content into {@link VersionedEntity VersionedEntities}.
   */
  static class LoadOneCommitLogsFile extends DoFn<ReadableFile, VersionedEntity> {

    @ProcessElement
    public void processElement(@Element ReadableFile file, OutputReceiver<VersionedEntity> out) {
      try {
        CommitLogImports.loadEntities(file.open()).forEach(out::output);
      } catch (IOException e) {
        // Let the pipeline retry the whole file.
        throw new RuntimeException(e);
      }
    }
  }
}
