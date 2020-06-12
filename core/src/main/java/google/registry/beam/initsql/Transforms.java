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

import google.registry.backup.VersionedEntity;
import org.apache.beam.sdk.io.Compression;
import org.apache.beam.sdk.io.FileIO;
import org.apache.beam.sdk.io.FileIO.ReadableFile;
import org.apache.beam.sdk.io.fs.EmptyMatchTreatment;
import org.apache.beam.sdk.io.fs.MatchResult.Metadata;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;

/**
 * Common {@link PTransform pipeline transforms} used in pipelines that load from both Datastore
 * export files and Nomulus CommitLog files.
 */
public class Transforms {

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
   * Returns a {@link PTransform} from file {@link Metadata} to {@link VersionedEntity} using
   * caller-provided {@code transformer}.
   */
  public static PTransform<PCollection<Metadata>, PCollection<VersionedEntity>> processFiles(
      DoFn<ReadableFile, VersionedEntity> transformer) {
    return new PTransform<PCollection<Metadata>, PCollection<VersionedEntity>>() {
      @Override
      public PCollection<VersionedEntity> expand(PCollection<Metadata> input) {
        return input
            .apply(FileIO.readMatches().withCompression(Compression.UNCOMPRESSED))
            .apply(transformer.getClass().getSimpleName(), ParDo.of(transformer));
      }
    };
  }
}
