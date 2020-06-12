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

import static google.registry.beam.initsql.BackupPaths.getExportFileNamePattern;
import static google.registry.beam.initsql.Transforms.processFiles;

import com.google.common.collect.ImmutableList;
import google.registry.backup.VersionedEntity;
import google.registry.tools.LevelDbLogReader;
import java.io.IOException;
import java.util.Collection;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.FileIO.ReadableFile;
import org.apache.beam.sdk.io.fs.MatchResult.Metadata;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;

/**
 * {@link PTransform Pipeline transforms} for loading from Datastore export files. They are all part
 * of a transformation that loads raw records from a Datastore export, and are broken apart for
 * testing.
 */
public class ExportLoadingTransforms {

  /**
   * Returns a {@link PTransform transform} that can generate a collection of patterns that match
   * all Datastore export files of the given {@code kinds}.
   */
  public static PTransform<PBegin, PCollection<String>> getDatastoreExportFilePatterns(
      String exportDir, Collection<String> kinds) {
    return Create.of(
            kinds.stream()
                .map(kind -> getExportFileNamePattern(exportDir, kind))
                .collect(ImmutableList.toImmutableList()))
        .withCoder(StringUtf8Coder.of());
  }

  /** Returns a {@link PTransform} from file {@link Metadata} to {@link VersionedEntity}. */
  public static PTransform<PCollection<Metadata>, PCollection<VersionedEntity>>
      loadExportDataFromFiles() {
    return processFiles(new LoadOneExportShard());
  }

  /**
   * Reads a LevelDb file and converts each raw record into a {@link VersionedEntity}. All such
   * entities use {@link Long#MIN_VALUE} as timestamp, so that they go before data from CommitLogs.
   *
   * <p>LevelDb files are not seekable because a large object may span multiple blocks. If a
   * sequential read fails, the file needs to be retried from the beginning.
   */
  private static class LoadOneExportShard extends DoFn<ReadableFile, VersionedEntity> {

    private static final long TIMESTAMP = Long.MIN_VALUE;

    @ProcessElement
    public void processElement(@Element ReadableFile file, OutputReceiver<VersionedEntity> output) {
      try {
        LevelDbLogReader.from(file.open())
            .forEachRemaining(record -> output.output(VersionedEntity.from(TIMESTAMP, record)));
      } catch (IOException e) {
        // Let the pipeline retry the whole file.
        throw new RuntimeException(e);
      }
    }
  }
}
