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

import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.flogger.FluentLogger;
import google.registry.beam.comparedb.LatestDatastoreSnapshotFinder;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.ofy.CommitLogCheckpoint;
import google.registry.model.replay.ReplicateToDatastoreAction;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Sleeper;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Synchronizes Datastore to a given SQL snapshot when SQL is the primary database.
 *
 * <p>The caller takes the responsibility for:
 *
 * <ul>
 *   <li>verifying the current migration stage
 *   <li>acquiring the {@link ReplicateToDatastoreAction#REPLICATE_TO_DATASTORE_LOCK_NAME
 *       replication lock}, and
 *   <li>while holding the lock, creating an SQL snapshot and invoking this action with the snapshot
 *       id
 *   <li>
 * </ul>
 *
 * The caller may release the replication lock upon receiving the response from this action. Please
 * refer to {@link google.registry.tools.ValidateDatastoreWithSqlCommand} for more information on
 * usage.
 *
 * <p>When invoked with an SQL snapshot id, this action plays SQL transactions up to that snapshot,
 * creates a new CommitLog checkpoint, and exports all CommitLogs to GCS up to this checkpoint. The
 * timestamp of this checkpoint can be used to recreate a Datastore snapshot that is equivalent to
 * the given SQL snapshot. If this action succeeds, the checkpoint timestamp is included in the
 * response.
 */
@Action(
    service = Service.BACKEND,
    path = SyncDatastoreToSqlSnapshotAction.PATH,
    method = Action.Method.POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
@DeleteAfterMigration
public class SyncDatastoreToSqlSnapshotAction implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String PATH = "/_dr/task/syncDatastoreToSqlSnapshot";

  static final String SQL_SNAPSHOT_ID_PARAM = "sqlSnapshotId";

  static final String SUCCESS_RESPONSE_TEMPLATE =
      "Datastore is up-to-date with provided SQL snapshot (%s). CommitLog timestamp is (%s).";

  private static final int COMMITLOGS_PRESENCE_CHECK_ATTEMPTS = 10;
  private static final Duration COMMITLOGS_PRESENCE_CHECK_DELAY = Duration.standardSeconds(6);

  private final Response response;
  private final Sleeper sleeper;

  @Config("commitLogGcsBucket")
  private final String gcsBucket;

  private final GcsDiffFileLister gcsDiffFileLister;
  private final LatestDatastoreSnapshotFinder datastoreSnapshotFinder;
  private final CommitLogCheckpointAction commitLogCheckpointAction;
  private final String sqlSnapshotId;

  @Inject
  SyncDatastoreToSqlSnapshotAction(
      Response response,
      Sleeper sleeper,
      @Config("commitLogGcsBucket") String gcsBucket,
      GcsDiffFileLister gcsDiffFileLister,
      LatestDatastoreSnapshotFinder datastoreSnapshotFinder,
      CommitLogCheckpointAction commitLogCheckpointAction,
      @Parameter(SQL_SNAPSHOT_ID_PARAM) String sqlSnapshotId) {
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
    logger.atInfo().log("Datastore validation invoked. SqlSnapshotId is %s.", sqlSnapshotId);

    try {
      CommitLogCheckpoint checkpoint = ensureDatabasesComparable(sqlSnapshotId);
      response.setStatus(SC_OK);
      response.setPayload(
          String.format(SUCCESS_RESPONSE_TEMPLATE, sqlSnapshotId, checkpoint.getCheckpointTime()));
      return;
    } catch (Exception e) {
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setPayload(e.getMessage());
    }
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
    for (int attempts = 0; attempts < COMMITLOGS_PRESENCE_CHECK_ATTEMPTS; attempts++) {
      try {
        gcsDiffFileLister.listDiffFiles(gcsBucket, exportStartTime, checkpoint.getCheckpointTime());
        return;
      } catch (IllegalStateException e) {
        // Gap in commitlog files. Fall through to sleep and retry.
        logger.atInfo().log("Commitlog files not yet found on GCS.");
      }
      sleeper.sleepInterruptibly(COMMITLOGS_PRESENCE_CHECK_DELAY);
    }
    throw new RuntimeException("Cannot find all commitlog files.");
  }
}
