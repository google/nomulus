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

import static google.registry.model.replay.ReplicateToDatastoreAction.REPLICATE_TO_DATASTORE_LOCK_LEASE_LENGTH;
import static google.registry.model.replay.ReplicateToDatastoreAction.REPLICATE_TO_DATASTORE_LOCK_NAME;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.MediaType;
import google.registry.backup.SyncDatastoreToSqlSnapshotAction;
import google.registry.beam.common.DatabaseSnapshot;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import google.registry.model.common.DatabaseMigrationStateSchedule.ReplayDirection;
import google.registry.model.server.Lock;
import google.registry.request.Action.Service;
import java.util.Optional;

/**
 * Validates asynchronously replicated data from the primary Cloud SQL database to Datastore.
 *
 * <p>This command suspends the replication process (by acquiring the replication lock), take a
 * snapshot of the Cloud SQL database, invokes a Nomulus server action to sync Datastore to this
 * snapshot (See {@link SyncDatastoreToSqlSnapshotAction} for details), and finally launches a BEAM
 * pipeline to compare Datastore with the given SQL snapshot.
 *
 * <p>This command does not lock up the SQL database. Normal processing can proceed.
 */
@Parameters(commandDescription = "Validates Datastore with the primary Cloud SQL database.")
public class ValidateDatastoreCommand extends ValidateDatabaseMigrationCommand {

  private static final Service NOMULUS_SERVICE = Service.BACKEND;

  @Override
  public void run() throws Exception {
    MigrationState state = DatabaseMigrationStateSchedule.getValueAtTime(clock.nowUtc());
    if (!state.getReplayDirection().equals(ReplayDirection.SQL_TO_DATASTORE)) {
      throw new IllegalStateException("Cannot validate Datastore in migration step " + state);
    }
    Optional<Lock> lock =
        Lock.acquireSql(
            REPLICATE_TO_DATASTORE_LOCK_NAME,
            null,
            REPLICATE_TO_DATASTORE_LOCK_LEASE_LENGTH,
            new FakeRequestStatusChecker(),
            false);
    if (!lock.isPresent()) {
      throw new IllegalStateException("Cannot acquire the async propagation lock.");
    }

    try {
      try (DatabaseSnapshot snapshot = DatabaseSnapshot.createSnapshot()) {
        System.out.printf("Obtained snapshot %s\n", snapshot.getSnapshotId());
        AppEngineConnection connectionToService = connection.withService(NOMULUS_SERVICE);
        String response =
            connectionToService.sendPostRequest(
                getNomulusEndpoint(snapshot.getSnapshotId()),
                ImmutableMap.<String, String>of(),
                MediaType.PLAIN_TEXT_UTF_8,
                "".getBytes(UTF_8));
        System.out.println(response);

        lock.ifPresent(Lock::releaseSql);
        lock = Optional.empty();

        // See SyncDatastoreToSqlSnapshotAction for response format.
        String latestCommitTimestamp =
            response.substring(response.lastIndexOf('(') + 1, response.lastIndexOf(')'));

        if (manualLaunchPipeline) {
          System.out.printf(
              "To launch the pipeline manually, use the following command:\n%s\n",
              getManualLaunchCommand(
                  "validate-datastore", snapshot.getSnapshotId(), latestCommitTimestamp));

          System.out.print("\nEnter any key to continue when the pipeline ends:");
          System.in.read();
        } else {
          launchPipelineAndWaitUntilFinish("validate-datastore", snapshot, latestCommitTimestamp);
        }
      }
    } finally {
      lock.ifPresent(Lock::releaseSql);
    }
  }

  private static String getNomulusEndpoint(String sqlSnapshotId) {
    return String.format(
        "%s?sqlSnapshotId=%s", SyncDatastoreToSqlSnapshotAction.PATH, sqlSnapshotId);
  }
}
