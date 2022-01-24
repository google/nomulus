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

import static google.registry.model.replay.ReplicateToDatastoreAction.REPLICATE_TO_DATASTORE_LOCK_NAME;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.MediaType;
import google.registry.beam.common.DatabaseSnapshot;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import google.registry.model.common.DatabaseMigrationStateSchedule.ReplayDirection;
import google.registry.model.replay.ReplicateToDatastoreAction;
import google.registry.model.server.Lock;
import google.registry.request.Action.Service;
import google.registry.util.Clock;
import google.registry.util.RequestStatusChecker;
import java.util.Optional;
import java.util.UUID;
import javax.inject.Inject;

@Parameters(commandDescription = "Syncs Datastore to a Cloud SQL snapshot")
public class SyncDatastoreToSqlCommand implements CommandWithConnection, CommandWithRemoteApi {

  private static final Service NOMULUS_SERVICE = Service.BACKEND;
  private static final String NOMULUS_ENDPOINT =
      "/_dr/task/validateDatastoreWithSql?sqlSnapshotId=%s";

  @Inject Clock clock;

  private AppEngineConnection connection;

  @Override
  public void setConnection(AppEngineConnection connection) {
    this.connection = connection;
  }

  @Override
  public void run() throws Exception {
    MigrationState state = DatabaseMigrationStateSchedule.getValueAtTime(clock.nowUtc());
    if (!state.getReplayDirection().equals(ReplayDirection.SQL_TO_DATASTORE)) {
      throw new IllegalStateException("Cannot sync Datastore to SQL in migration step " + state);
    }
    Optional<Lock> lock =
        Lock.acquireSql(
            REPLICATE_TO_DATASTORE_LOCK_NAME,
            null,
            ReplicateToDatastoreAction.REPLICATE_TO_DATASTORE_LOCK_LEASE_LENGTH,
            new DummyRequestStatusChecker(),
            false);
    if (!lock.isPresent()) {
      throw new IllegalStateException("Cannot acquire the async propagation lock.");
    }

    try {
      try (DatabaseSnapshot snapshot = DatabaseSnapshot.createSnapshot()) {
        System.out.printf("Obtained snapshot %s\n", snapshot.getSnapshotId());
        // Adapted from CurlCommand
        AppEngineConnection connectionToService = connection.withService(NOMULUS_SERVICE);
        String response =
            connectionToService.sendPostRequest(
                String.format(NOMULUS_ENDPOINT, snapshot.getSnapshotId()),
                ImmutableMap.<String, String>of(),
                MediaType.PLAIN_TEXT_UTF_8,
                "".getBytes(UTF_8));
        System.out.println(response);
        System.out.print("\nEnter any key to continue:");
        System.in.read();
      }
    } finally {
      lock.ifPresent(Lock::releaseSql);
    }
  }

  static class DummyRequestStatusChecker implements RequestStatusChecker {

    @Override
    public String getLogId() {
      return SyncDatastoreToSqlCommand.class.getSimpleName() + "-" + UUID.randomUUID();
    }

    @Override
    public boolean isRunning(String requestLogId) {
      return false;
    }
  }
}
