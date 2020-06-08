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

import static com.google.common.base.Preconditions.checkState;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.appengine.api.datastore.Entity;
import com.googlecode.objectify.Key;
import google.registry.backup.CommitLogExports;
import google.registry.model.ofy.CommitLogCheckpoint;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.tools.LevelDbFileBuilder;
import java.io.File;
import java.io.IOException;
import java.util.Set;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

/**
 * Wrapper of a Datastore test instance that can generate backups.
 *
 * <p>A Datastore backup consists of an unsynchronized data export and a sequence of incremental
 * Commit Logs that overlap with the export process. Together they can be used to recreate a
 * consistent snapshot of the Datastore.
 *
 * <p>For convenience of test-writing, the {@link #fakeClock} is advanced by 1 millisecond after
 * every transaction is invoked on this store, ensuring strictly increasing timestamps on causally
 * dependent transactions. In production, the same ordering is ensured by sleep and retry.
 */
class BackupTestStore implements AutoCloseable {

  private static final DateTimeFormatter EXPORT_TIMESTAMP_FORMAT =
      DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss_SSS");

  private final FakeClock fakeClock;
  private AppEngineRule appEngine;

  private CommitLogCheckpoint prevCommitLogCheckpoint;

  BackupTestStore(FakeClock fakeClock) throws Exception {
    this.fakeClock = fakeClock;
    this.appEngine =
        new AppEngineRule.Builder()
            .withDatastore()
            .withoutCannedData()
            .withClock(fakeClock)
            .build();
    this.appEngine.beforeEach(null);
  }

  void transact(Iterable<Object> deletes, Iterable<Object> newOrUpdated) {
    tm().transact(
            () -> {
              ofy().delete().entities(deletes);
              ofy().save().entities(newOrUpdated);
            });
    fakeClock.advanceOneMilli();
  }

  /** Inserts or updates {@code entities} in the Datastore. */
  @SafeVarargs
  final void insertOrUpdate(Object... entities) {
    tm().transact(() -> ofy().save().entities(entities).now());
    fakeClock.advanceOneMilli();
  }

  /** Deletes {@code entities} from the Datastore. */
  @SafeVarargs
  final void delete(Object... entities) {
    tm().transact(() -> ofy().delete().entities(entities).now());
    fakeClock.advanceOneMilli();
  }

  /**
   * Exports entities of the caller provided types and returns the directory where data is exported.
   *
   * @param exportRootPath path to the root directory of all exports. A subdirectory will be created
   *     for this export
   * @param pojoTypes java class of all entities to be exported
   * @param excludes {@link Set} of {@link Key keys} of the entities not to export.This can be used
   *     to simulate an inconsistent export
   * @return directory where data is exported
   */
  File export(String exportRootPath, Iterable<Class<?>> pojoTypes, Set<Key<?>> excludes)
      throws IOException {
    File exportDirectory = getExportDirectory(exportRootPath);
    for (Class<?> pojoType : pojoTypes) {
      File perKindFile =
          new File(
              BackupPaths.getExportFileNameByShard(
                  exportDirectory.getAbsolutePath(), Key.getKind(pojoType), 0));
      checkState(
          perKindFile.getParentFile().mkdirs(),
          "Failed to create per-kind export directory for %s.",
          perKindFile.getParentFile().getAbsolutePath());
      exportOneKind(perKindFile, pojoType, excludes);
    }
    return exportDirectory;
  }

  private void exportOneKind(File perKindFile, Class<?> pojoType, Set<Key<?>> excludes)
      throws IOException {
    LevelDbFileBuilder builder = new LevelDbFileBuilder(perKindFile);
    for (Object pojo : ofy().load().type(pojoType).iterable()) {
      if (!excludes.contains(Key.create(pojo))) {
        try {
          builder.addEntity(toEntity(pojo));
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    }
    builder.build();
  }

  File saveCommitLogs(String commitLogDir) {
    CommitLogCheckpoint checkpoint = CommitLogExports.computeCheckpoint(ofy(), fakeClock);
    File commitLogFile =
        CommitLogExports.saveCommitLogs(commitLogDir, prevCommitLogCheckpoint, checkpoint);
    prevCommitLogCheckpoint = checkpoint;
    return commitLogFile;
  }

  @Override
  public void close() throws Exception {
    if (appEngine != null) {
      appEngine.afterEach(null);
      appEngine = null;
    }
  }

  private Entity toEntity(Object pojo) {
    return tm().transactNew(() -> ofy().save().toEntity(pojo));
  }

  private File getExportDirectory(String exportRootPath) {
    File exportDirectory =
        new File(exportRootPath, fakeClock.nowUtc().toString(EXPORT_TIMESTAMP_FORMAT));
    checkState(
        exportDirectory.mkdirs(),
        "Failed to create export directory %s.",
        exportDirectory.getAbsolutePath());
    return exportDirectory;
  }
}
