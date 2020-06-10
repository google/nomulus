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

package google.registry.backup;

import static com.google.common.base.Preconditions.checkState;
import static google.registry.backup.BackupUtils.createDeserializingIterator;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import google.registry.model.ImmutableObject;
import google.registry.model.ofy.CommitLogCheckpoint;
import google.registry.model.ofy.CommitLogManifest;
import google.registry.model.ofy.CommitLogMutation;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Iterator;
import java.util.stream.Stream;

/**
 * Helpers for reading CommitLog records from a file.
 *
 * <p>This class is adapted from {@link RestoreCommitLogsAction}, and will be used in the initial
 * population of the Cloud SQL database.
 */
public final class CommitLogImports {

  private CommitLogImports() {}

  /**
   * Returns entities in an {@code inputStream} (from a single CommitLog file) as an {@link
   * ImmutableList} of {@link VersionedEntity} records. Upon completion the {@code inputStream} is
   * closed.
   *
   * <p>The returned list may be empty, since CommitLogs are written at fixed intervals regardless
   * if actual changes exist.
   *
   * <p>A CommitLog file starts with a {@link CommitLogCheckpoint}, followed by (repeated)
   * subsequences of [{@link CommitLogManifest}, [{@link CommitLogMutation}] ...]. Each subsequence
   * represents the changes in one transaction. The {@code CommitLogManifest} contains deleted
   * entity keys, whereas each {@code CommitLogMutation} contains one whole entity.
   */
  public static ImmutableList<VersionedEntity> loadEntities(InputStream inputStream) {
    try (AppEngineEnvironment appEngineEnvironment = new AppEngineEnvironment();
        InputStream input = new BufferedInputStream(inputStream)) {
      Iterator<ImmutableObject> commitLogs = createDeserializingIterator(input);
      checkState(commitLogs.hasNext());
      checkState(commitLogs.next() instanceof CommitLogCheckpoint);

      return Streams.stream(commitLogs)
          .map(
              e ->
                  e instanceof CommitLogManifest
                      ? VersionedEntity.fromManifest((CommitLogManifest) e)
                      : Stream.of(VersionedEntity.fromMutation((CommitLogMutation) e)))
          .flatMap(s -> s)
          .collect(ImmutableList.toImmutableList());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Covenience method that adapts {@link #loadEntities(InputStream)} to a {@link File}. */
  public static ImmutableList<VersionedEntity> loadEntities(File commitLogFile) {
    try {
      return loadEntities(new FileInputStream(commitLogFile));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Covenience method that adapts {@link #loadEntities(InputStream)} to a {@link
   * ReadableByteChannel}.
   */
  public static ImmutableList<VersionedEntity> loadEntities(ReadableByteChannel channel) {
    return loadEntities(Channels.newInputStream(channel));
  }
}
