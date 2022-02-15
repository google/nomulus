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

package google.registry.tools;

import static google.registry.backup.ReplayCommitLogsToSqlAction.REPLAY_TO_SQL_LOCK_LEASE_LENGTH;
import static google.registry.backup.ReplayCommitLogsToSqlAction.REPLAY_TO_SQL_LOCK_NAME;

import com.beust.jcommander.Parameters;
import google.registry.beam.common.DatabaseSnapshot;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import google.registry.model.common.DatabaseMigrationStateSchedule.ReplayDirection;
import google.registry.model.replay.SqlReplayCheckpoint;
import google.registry.model.server.Lock;
import google.registry.persistence.transaction.TransactionManagerFactory;
import java.util.Optional;
import org.joda.time.DateTime;

/**
 * Validates asynchronously replicated data from the primary Datastore to Cloud SQL.
 *
 * <p>This command suspends the replication process (by acquiring the replication lock), take a
 * snapshot of the Cloud SQL database, finds the corresponding Datastore snapshot, and finally
 * launches a BEAM pipeline to compare the two snapshots.
 *
 * <p>This command does not lock up either database. Normal processing can proceed.
 */
@Parameters(commandDescription = "Validates Cloud SQL with the primary Datastore.")
public class ValidateSqlCommand extends ValidateDatabaseMigrationCommand {

  @Override
  public void run() throws Exception {
    MigrationState state = DatabaseMigrationStateSchedule.getValueAtTime(clock.nowUtc());
    if (!state.getReplayDirection().equals(ReplayDirection.DATASTORE_TO_SQL)) {
      throw new IllegalStateException("Cannot validate SQL in migration step " + state);
    }
    Optional<Lock> lock =
        Lock.acquireSql(
            REPLAY_TO_SQL_LOCK_NAME,
            null,
            REPLAY_TO_SQL_LOCK_LEASE_LENGTH,
            new FakeRequestStatusChecker(),
            false);
    if (!lock.isPresent()) {
      throw new IllegalStateException("Cannot acquire the async propagation lock.");
    }

    try {
      DateTime latestCommitLogTime =
          TransactionManagerFactory.jpaTm().transact(() -> SqlReplayCheckpoint.get());
      try (DatabaseSnapshot databaseSnapshot = DatabaseSnapshot.createSnapshot()) {
        // Eagerly release the commitlog replay lock so that replay can resume.
        lock.ifPresent(Lock::releaseSql);
        lock = Optional.empty();

        System.out.printf(
            "Start comparison with SQL snapshot (%s) and CommitLog timestamp (%s).\n",
            databaseSnapshot.getSnapshotId(), latestCommitLogTime);

        if (manualLaunchPipeline) {
          System.out.printf(
              "To launch the pipeline manually, use the following command:\n%s\n",
              getManualLaunchCommand(
                  "validate-sql",
                  databaseSnapshot.getSnapshotId(),
                  latestCommitLogTime.toString()));

          System.out.print("\nEnter any key to continue when the pipeline ends:");
          System.in.read();
        } else {
          launchPipelineAndWaitUntilFinish(
              "validate-sql", databaseSnapshot, latestCommitLogTime.toString());
        }
      }
    } finally {
      lock.ifPresent(Lock::releaseSql);
    }
  }
}
