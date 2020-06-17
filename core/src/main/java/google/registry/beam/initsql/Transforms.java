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
import static google.registry.beam.initsql.BackupPaths.getCommitLogTimestamp;
import static google.registry.beam.initsql.BackupPaths.getExportFilePatterns;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;

import avro.shaded.com.google.common.collect.Iterators;
import com.google.common.annotations.VisibleForTesting;
import google.registry.backup.CommitLogImports;
import google.registry.backup.VersionedEntity;
import google.registry.tools.LevelDbLogReader;
import java.util.Collection;
import java.util.Iterator;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.Compression;
import org.apache.beam.sdk.io.FileIO;
import org.apache.beam.sdk.io.FileIO.ReadableFile;
import org.apache.beam.sdk.io.fs.EmptyMatchTreatment;
import org.apache.beam.sdk.io.fs.MatchResult.Metadata;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.ProcessFunction;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.DateTime;

/**
 * {@link PTransform Pipeline transforms} used in pipelines that load from both Datastore export
 * files and Nomulus CommitLog files.
 */
public final class Transforms {

  private Transforms() {}

  /**
   * The commitTimestamp assigned to all entities loaded from a Datastore export file. The exact
   * value does not matter, but it must be lower than the timestamps of real CommitLog records.
   */
  @VisibleForTesting static final long EXPORT_ENTITY_TIME_STAMP = START_OF_TIME.getMillis();

  /**
   * Returns a {@link PTransform transform} that can generate a collection of patterns that match
   * all Datastore CommitLog files.
   */
  public static PTransform<PBegin, PCollection<String>> getCommitLogFilePatterns(
      String commitLogDir) {
    return toStringPCollection(BackupPaths.getCommitLogFilePatterns(commitLogDir));
  }

  /**
   * Returns a {@link PTransform transform} that can generate a collection of patterns that match
   * all Datastore export files of the given {@code kinds}.
   */
  public static PTransform<PBegin, PCollection<String>> getDatastoreExportFilePatterns(
      String exportDir, Collection<String> kinds) {
    return toStringPCollection(getExportFilePatterns(exportDir, kinds));
  }

  public static PTransform<PBegin, PCollection<String>> getCloudSqlConnectionInfoFilePatterns(
      String gcpProjectName) {
    return toStringPCollection(BackupPaths.getCloudSQLCredentialFilePatterns(gcpProjectName));
  }

  /**
   * Returns a {@link PTransform} from file name patterns to file {@link Metadata Metadata records}.
   */
  public static PTransform<PCollection<String>, PCollection<Metadata>> getFilesByPatterns() {
    return new PTransform<PCollection<String>, PCollection<Metadata>>() {
      @Override
      public PCollection<Metadata> expand(PCollection<String> input) {
        return input.apply(FileIO.matchAll().withEmptyMatchTreatment(EmptyMatchTreatment.DISALLOW));
      }
    };
  }

  /**
   * Returns CommitLog files with timestamps between {@code fromTime} (inclusive) and {@code
   * endTime} (exclusive).
   */
  public static PTransform<PCollection<? extends String>, PCollection<String>>
      filterCommitLogsByTime(DateTime fromTime, DateTime toTime) {
    return ParDo.of(new FilterCommitLogFileByTime(fromTime, toTime));
  }

  /** Returns a {@link PTransform} from file {@link Metadata} to {@link VersionedEntity}. */
  public static PTransform<PCollection<Metadata>, PCollection<VersionedEntity>>
      loadExportDataFromFiles() {
    return processFiles(
        new BackupFileReader(
            file ->
                Iterators.transform(
                    LevelDbLogReader.from(file.open()),
                    (byte[] bytes) -> VersionedEntity.from(EXPORT_ENTITY_TIME_STAMP, bytes))));
  }

  /** Returns a {@link PTransform} from file {@link Metadata} to {@link VersionedEntity}. */
  public static PTransform<PCollection<Metadata>, PCollection<VersionedEntity>>
      loadCommitLogsFromFiles() {
    return processFiles(
        new BackupFileReader(file -> CommitLogImports.loadEntities(file.open()).iterator()));
  }

  /**
   * Returns a {@link PTransform} that produces a {@link PCollection} containing all elements in the
   * given {@link Iterable}.
   */
  static PTransform<PBegin, PCollection<String>> toStringPCollection(Iterable<String> strings) {
    return Create.of(strings).withCoder(StringUtf8Coder.of());
  }

  /**
   * Returns a {@link PTransform} from file {@link Metadata} to {@link VersionedEntity} using
   * caller-provided {@code transformer}.
   */
  static PTransform<PCollection<Metadata>, PCollection<VersionedEntity>> processFiles(
      DoFn<ReadableFile, VersionedEntity> transformer) {
    return new PTransform<PCollection<Metadata>, PCollection<VersionedEntity>>() {
      @Override
      public PCollection<VersionedEntity> expand(PCollection<Metadata> input) {
        return input
            .apply(FileIO.readMatches().withCompression(Compression.UNCOMPRESSED))
            .apply(transformer.getClass().getSimpleName(), ParDo.of(transformer));
        // TODO(weiminyu): reshuffle to enable dynamic work rebalance per beam dev guide
      }
    };
  }

  private static class FilterCommitLogFileByTime extends DoFn<String, String> {
    private final DateTime fromTime;
    private final DateTime toTime;

    public FilterCommitLogFileByTime(DateTime fromTime, DateTime toTime) {
      checkNotNull(fromTime, "fromTime");
      checkNotNull(toTime, "toTime");
      checkArgument(
          fromTime.isBefore(toTime),
          "Invalid time range: fromTime (%s) is before endTime (%s)",
          fromTime,
          toTime);
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
   * Reads from a Datastore backup file and converts its content into {@link VersionedEntity
   * VersionedEntities}.
   *
   * <p>The input file may be either a LevelDb file from a Datastore export or a CommitLog file
   * generated by the Nomulus server. In either case, the file contains variable-length records and
   * must be read sequentially from the beginning. If the read fails, the file needs to be retried
   * from the beginning.
   */
  private static class BackupFileReader extends DoFn<ReadableFile, VersionedEntity> {
    private final ProcessFunction<ReadableFile, Iterator<VersionedEntity>> reader;

    private BackupFileReader(ProcessFunction<ReadableFile, Iterator<VersionedEntity>> reader) {
      this.reader = reader;
    }

    @ProcessElement
    public void processElement(@Element ReadableFile file, OutputReceiver<VersionedEntity> out) {
      try {
        reader.apply(file).forEachRemaining(out::output);
      } catch (Exception e) {
        // Let the pipeline use default retry strategy on the whole file. For GCP Dataflow this
        // means retrying up to 4 times (may include other files grouped with this one), and failing
        // the pipeline if no success.
        throw new RuntimeException(e);
      }
    }
  }
}
