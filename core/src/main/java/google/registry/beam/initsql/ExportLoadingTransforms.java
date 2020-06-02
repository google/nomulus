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
import static google.registry.beam.initsql.BackupPaths.getKindFromFileName;
import static org.apache.beam.sdk.values.TypeDescriptors.kvs;
import static org.apache.beam.sdk.values.TypeDescriptors.strings;

import com.google.common.collect.ImmutableList;
import google.registry.tools.LevelDbLogReader;
import java.io.IOException;
import java.util.Collection;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.Compression;
import org.apache.beam.sdk.io.FileIO;
import org.apache.beam.sdk.io.FileIO.ReadableFile;
import org.apache.beam.sdk.io.fs.EmptyMatchTreatment;
import org.apache.beam.sdk.io.fs.MatchResult.Metadata;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptor;

/**
 * {@link PTransform Pipeline transforms} for loading from Datastore export files. They are all part
 * of a transformation that loads raw records from a Datastore export, and are broken apart for
 * testing.
 *
 * <p>We drop the 'kind' information in {@link #getDatastoreExportFilePatterns} and recover it later
 * using the file paths. Although we could have kept it by passing around {@link KV key-value
 * pairs}, the code would be more complicated, especially in {@link #loadDataFromFiles()}.
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
   * Returns a {@link PTransform} from file {@link Metadata} to {@link KV Key-Value pairs} with
   * entity 'kind' as key and raw record as value.
   */
  public static PTransform<PCollection<Metadata>, PCollection<KV<String, byte[]>>>
      loadDataFromFiles() {
    return new PTransform<PCollection<Metadata>, PCollection<KV<String, byte[]>>>() {
      @Override
      public PCollection<KV<String, byte[]>> expand(PCollection<Metadata> input) {
        return input
            .apply(FileIO.readMatches().withCompression(Compression.UNCOMPRESSED))
            .apply(
                MapElements.into(kvs(strings(), TypeDescriptor.of(ReadableFile.class)))
                    .via(file -> KV.of(getKindFromFileName(file.getMetadata().toString()), file)))
            .apply("Load One LevelDb File", ParDo.of(new LoadOneFile()));
      }
    };
  }

  /**
   * Reads a LevelDb file and converts each raw record into a {@link KV pair} of kind and bytes.
   *
   * <p>LevelDb files are not seekable because a large object may span multiple blocks. If a
   * sequential read fails, the file needs to be retried from the beginning.
   */
  private static class LoadOneFile extends DoFn<KV<String, ReadableFile>, KV<String, byte[]>> {

    @ProcessElement
    public void processElement(
        @Element KV<String, ReadableFile> kv, OutputReceiver<KV<String, byte[]>> output) {
      try {
        LevelDbLogReader.from(kv.getValue().open())
            .forEachRemaining(record -> output.output(KV.of(kv.getKey(), record)));
      } catch (IOException e) {
        // Let the pipeline retry the whole file.
        throw new RuntimeException(e);
      }
    }
  }
}
