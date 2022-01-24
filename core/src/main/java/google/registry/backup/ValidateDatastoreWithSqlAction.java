// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.model.replay.ReplicateToDatastoreAction.REPLICATE_TO_DATASTORE_LOCK_NAME;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.base.Stopwatch;
import com.google.common.flogger.FluentLogger;
import google.registry.beam.comparedb.LatestDatastoreSnapshotFinder;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.ofy.CommitLogCheckpoint;
import google.registry.model.replay.ReplicateToDatastoreAction;
import google.registry.model.server.Lock;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.util.RequestStatusChecker;
import google.registry.util.Sleeper;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** */
@Action(
    service = Service.BACKEND,
    path = ValidateDatastoreWithSqlAction.PATH,
    method = Action.Method.POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
@DeleteAfterMigration
public class ValidateDatastoreWithSqlAction implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final String PATH = "/_dr/task/validateDatastoreWithSql";

  static final String SQL_SNAPSHOT_ID_PARAM = "sqlSnapshotId";

  private static final java.time.Duration REPLAY_LOCK_ACQUIRE_TIMEOUT =
      java.time.Duration.ofMinutes(6);
  private static final java.time.Duration REPLAY_LOCK_ACQUIRE_DELAY =
      java.time.Duration.ofSeconds(30);

  private final Clock clock;
  private final RequestStatusChecker requestStatusChecker;
  private final Response response;
  private final Sleeper sleeper;

  @Config("commitLogGcsBucket")
  private final String gcsBucket;

  private final GcsDiffFileLister gcsDiffFileLister;
  private final LatestDatastoreSnapshotFinder datastoreSnapshotFinder;
  private final CommitLogCheckpointAction commitLogCheckpointAction;
  private final Optional<String> sqlSnapshotId;

  @Inject
  ValidateDatastoreWithSqlAction(
      Clock clock,
      RequestStatusChecker requestStatusChecker,
      Response response,
      Sleeper sleeper,
      @Config("commitLogGcsBucket") String gcsBucket,
      GcsDiffFileLister gcsDiffFileLister,
      LatestDatastoreSnapshotFinder datastoreSnapshotFinder,
      CommitLogCheckpointAction commitLogCheckpointAction,
      @Parameter(SQL_SNAPSHOT_ID_PARAM) Optional<String> sqlSnapshotId) {
    this.clock = clock;
    this.requestStatusChecker = requestStatusChecker;
    this.response = response;
    this.sleeper = sleeper;
    this.gcsBucket = gcsBucket;
    this.gcsDiffFileLister = gcsDiffFileLister;
    this.datastoreSnapshotFinder = datastoreSnapshotFinder;
    this.commitLogCheckpointAction = commitLogCheckpointAction;
    this.sqlSnapshotId = sqlSnapshotId;
  }

  @Override
  public void run() {
    logger.atInfo().log(
        "Datastore validation invoked. SqlSnapshotId is %s.", sqlSnapshotId.orElse("not present"));

    if (sqlSnapshotId.isPresent()) {
      CommitLogCheckpoint checkpoint = ensureDatabasesComparable(sqlSnapshotId.get());
      response.setStatus(SC_OK);
      response.setPayload(
          String.format(
              "Datastore is up-to-date with provided SQL snapshot (%s). CommitLog timestamp is %s",
              sqlSnapshotId.get(), checkpoint.getCheckpointTime()));
      return;
    }
    response.setStatus(SC_OK);
    response.setPayload("NOP");
    //
    // MigrationState state = DatabaseMigrationStateSchedule.getValueAtTime(clock.nowUtc());
    // if (!state.getReplayDirection().equals(ReplayDirection.SQL_TO_DATASTORE)) {
    //   String message = String.format("Validation is meaningless in migration phase %s.", state);
    //   logger.atInfo().log(message);
    //   response.setStatus(SC_NO_CONTENT);
    //   response.setPayload(message);
    //   return;
    // }
    // Lock lock = acquireSqlTransactionReplayLock().orElse(null);
    // if (lock == null) {
    //   String message = "Can't acquire ReplicateToDatastoreAction lock, aborting.";
    //   logger.atSevere().log(message);
    //   response.setStatus(SC_SERVICE_UNAVAILABLE);
    //   response.setPayload(message);
    //   return;
    // }
    //
    // try {
    //   try (DatabaseSnapshot databaseSnapshot = DatabaseSnapshot.createSnapshot()) {
    //     ensureDatabasesComparable(databaseSnapshot.getSnapshotId());
    //     // Release the lock so that normal replay can resume.
    //     lock.releaseSql();
    //     lock = null;
    //     // TODO: launch beam pipeline to compare the snapshots.
    //   }
    // } catch (RuntimeException e) {
    //   logger.atSevere().withCause(e).log("Internal error.");
    //   response.setStatus(SC_INTERNAL_SERVER_ERROR);
    //   response.setPayload(e.getMessage());
    //   return;
    // } finally {
    //   if (lock != null) {listDiffFiles
    //     lock.releaseSql();
    //   }
    // }
  }

  private CommitLogCheckpoint ensureDatabasesComparable(String sqlSnapshotId) {
    // Replicate SQL transaction to Datastore, up to when this snapshot is taken.
    int playbacks = ReplicateToDatastoreAction.replayAllTransactions(Optional.of(sqlSnapshotId));
    logger.atInfo().log("Played %s SQL transactions.", playbacks);

    Optional<CommitLogCheckpoint> checkpoint = exportCommitLogs();
    if (!checkpoint.isPresent()) {
      throw new RuntimeException("Cannot create CommitLog checkpoint");
    }
    logger.atInfo().log(
        "CommitLog checkpoint created at %s.", checkpoint.get().getCheckpointTime());
    verifyCommitLogsPersisted(checkpoint.get());
    return checkpoint.get();
  }

  private Optional<CommitLogCheckpoint> exportCommitLogs() {
    // Trigger an async CommitLog export, and wait until the checkpoint is exported to GCS.
    // Although we can add support to synchronous execution, it can disrupt the export cadence
    // when the system is busy
    Optional<CommitLogCheckpoint> checkpoint =
        commitLogCheckpointAction.createCheckPointAndStartAsyncExport();

    if (!checkpoint.isPresent()) {
      commitLogCheckpointAction.createCheckPointAndStartAsyncExport();
    }
    return checkpoint;
  }

  private void verifyCommitLogsPersisted(CommitLogCheckpoint checkpoint) {
    DateTime exportStartTime =
        datastoreSnapshotFinder
            .getSnapshotInfo(checkpoint.getCheckpointTime().toInstant())
            .exportInterval()
            .getStart();
    logger.atInfo().log("Found Datastore export at %s", exportStartTime);
    for (int i = 0; i < 5; i++) {
      try {
        gcsDiffFileLister.listDiffFiles(gcsBucket, exportStartTime, checkpoint.getCheckpointTime());
        return;
      } catch (IllegalStateException e) {
        // Gap in commitlog files. Fall through to sleep and retry.
      }

      sleeper.sleepInterruptibly(Duration.standardSeconds(30));
    }
    throw new RuntimeException("Cannot find all commitlog files.");
  }

  private Optional<Lock> acquireSqlTransactionReplayLock() {
    Stopwatch stopwatch = Stopwatch.createStarted();
    while (stopwatch.elapsed().minus(REPLAY_LOCK_ACQUIRE_TIMEOUT).isNegative()) {
      Optional<Lock> lock =
          Lock.acquireSql(
              REPLICATE_TO_DATASTORE_LOCK_NAME,
              null,
              ReplicateToDatastoreAction.REPLICATE_TO_DATASTORE_LOCK_LEASE_LENGTH,
              requestStatusChecker,
              false);
      if (lock.isPresent()) {
        return lock;
      }
      logger.atInfo().log("Failed to acquired CommitLog Replay lock. Will retry...");
      try {
        Thread.sleep(REPLAY_LOCK_ACQUIRE_DELAY.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted.");
      }
    }
    return Optional.empty();
  }
}
