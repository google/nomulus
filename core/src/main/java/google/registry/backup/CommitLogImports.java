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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.backup.BackupUtils.createDeserializingIterator;

import com.google.common.collect.ImmutableList;
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
   * ImmutableList} of {@link ImmutableList}s of {@link VersionedEntity} records where the inner
   * lists each consist of one transaction. Upon completion the {@code inputStream} is closed.
   *
   * <p>The returned list may be empty, since CommitLogs are written at fixed intervals regardless
   * if actual changes exist. Each sublist, however, will not be empty.
   *
   * <p>A CommitLog file starts with a {@link CommitLogCheckpoint}, followed by (repeated)
   * subsequences of [{@link CommitLogManifest}, [{@link CommitLogMutation}] ...]. Each subsequence
   * represents the changes in one transaction. The {@code CommitLogManifest} contains deleted
   * entity keys, whereas each {@code CommitLogMutation} contains one whole entity.
   */
  static ImmutableList<ImmutableList<VersionedEntity>> loadEntitiesByTransaction(
      InputStream inputStream) {
    try (InputStream input = new BufferedInputStream(inputStream)) {
      Iterator<ImmutableObject> commitLogs = createDeserializingIterator(input, false);
      checkState(commitLogs.hasNext());
      checkState(commitLogs.next() instanceof CommitLogCheckpoint);

      ImmutableList.Builder<ImmutableList<VersionedEntity>> resultBuilder =
          new ImmutableList.Builder<>();
      ImmutableList.Builder<VersionedEntity> currentTransactionBuilder =
          new ImmutableList.Builder<>();

      while (commitLogs.hasNext()) {
        ImmutableObject currentObject = commitLogs.next();
        if (currentObject instanceof CommitLogManifest) {
          // CommitLogManifest means we are starting a new transaction
          addIfNonempty(resultBuilder, currentTransactionBuilder);
          currentTransactionBuilder = new ImmutableList.Builder<>();
          VersionedEntity.fromManifest((CommitLogManifest) currentObject)
              .forEach(currentTransactionBuilder::add);
        } else if (currentObject instanceof CommitLogMutation) {
          currentTransactionBuilder.add(
              VersionedEntity.fromMutation((CommitLogMutation) currentObject));
        } else {
          throw new IllegalStateException(
              String.format("Unknown entity type %s in commit logs", currentObject.getClass()));
        }
      }
      // Add the last transaction in (if it's not empty)
      addIfNonempty(resultBuilder, currentTransactionBuilder);
      return resultBuilder.build();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

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
  static ImmutableList<VersionedEntity> loadEntities(InputStream inputStream) {
    return loadEntitiesByTransaction(inputStream).stream()
        .flatMap(ImmutableList::stream)
        .collect(toImmutableList());
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

  private static void addIfNonempty(
      ImmutableList.Builder<ImmutableList<VersionedEntity>> resultBuilder,
      ImmutableList.Builder<VersionedEntity> currentTransactionBuilder) {
    ImmutableList<VersionedEntity> currentTransaction = currentTransactionBuilder.build();
    if (!currentTransaction.isEmpty()) {
      resultBuilder.add(currentTransaction);
    }
  }
}
