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

import static google.registry.beam.BeamUtils.createJobName;
import static google.registry.model.replay.ReplicateToDatastoreAction.REPLICATE_TO_DATASTORE_LOCK_NAME;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dataflow.model.Job;
import com.google.api.services.dataflow.model.LaunchFlexTemplateParameter;
import com.google.api.services.dataflow.model.LaunchFlexTemplateRequest;
import com.google.api.services.dataflow.model.LaunchFlexTemplateResponse;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.MediaType;
import google.registry.backup.SyncDatastoreToSqlSnapshotAction;
import google.registry.beam.common.DatabaseSnapshot;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import google.registry.model.common.DatabaseMigrationStateSchedule.ReplayDirection;
import google.registry.model.replay.ReplicateToDatastoreAction;
import google.registry.model.server.Lock;
import google.registry.request.Action.Service;
import google.registry.util.Clock;
import google.registry.util.RequestStatusChecker;
import google.registry.util.Sleeper;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import javax.inject.Inject;
import org.joda.time.Duration;

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
@Parameters(commandDescription = "Validates Datastore with Cloud SQL.")
public class ValidateDatastoreWithSqlCommand
    implements CommandWithConnection, CommandWithRemoteApi {

  private static final Service NOMULUS_SERVICE = Service.BACKEND;

  private static final String PIPELINE_NAME = "validate_datastore_pipeline";

  // States indicating a job is not finished yet.
  private static final ImmutableSet<String> DATAFLOW_JOB_RUNNING_STATES =
      ImmutableSet.of(
          "JOB_STATE_RUNNING", "JOB_STATE_STOPPED", "JOB_STATE_PENDING", "JOB_STATE_QUEUED");

  private static final Duration JOB_POLLING_INTERVAL = Duration.standardSeconds(60);

  @Parameter(
      names = {"-m", "--manual"},
      description =
          "If true, let user launch the comparison pipeline manually out of band. "
              + "Command will wait for user key-press to exit after syncing Datastore.")
  boolean manualLaunchPipeline;

  @Inject Clock clock;
  @Inject Dataflow dataflow;

  @Inject
  @Config("defaultJobRegion")
  String jobRegion;

  @Inject
  @Config("beamStagingBucketUrl")
  String stagingBucketUrl;

  @Inject
  @Config("projectId")
  String projectId;

  @Inject Sleeper sleeper;

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
          System.out.print("\nEnter any key to continue when the pipeline ends:");
          System.in.read();
        } else {
          Job pipelineJob =
              launchComparisonPipeline(snapshot.getSnapshotId(), latestCommitTimestamp).getJob();
          String jobId = pipelineJob.getId();

          System.out.printf(
              "Launched comparison pipeline %s (%s).\n", pipelineJob.getName(), jobId);

          while (DATAFLOW_JOB_RUNNING_STATES.contains(getDataflowJobStatus(jobId))) {
            sleeper.sleepInterruptibly(JOB_POLLING_INTERVAL);
          }
          System.out.printf(
              "Pipeline ended with %s state. Please check counters for results.\n",
              getDataflowJobStatus(jobId));
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

  private LaunchFlexTemplateResponse launchComparisonPipeline(
      String sqlSnapshotId, String latestCommitLogTimestamp) {
    try {
      LaunchFlexTemplateParameter parameter =
          new LaunchFlexTemplateParameter()
              .setJobName(createJobName("validate-datastore", clock))
              .setContainerSpecGcsPath(
                  String.format("%s/%s_metadata.json", stagingBucketUrl, PIPELINE_NAME))
              .setParameters(
                  ImmutableMap.of(
                      "sqlSnapshotId",
                      sqlSnapshotId,
                      "latestCommitLogTimestamp",
                      latestCommitLogTimestamp,
                      "registryEnvironment",
                      RegistryToolEnvironment.get().name()));
      return dataflow
          .projects()
          .locations()
          .flexTemplates()
          .launch(
              projectId, jobRegion, new LaunchFlexTemplateRequest().setLaunchParameter(parameter))
          .execute();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private String getDataflowJobStatus(String jobId) {
    try {
      return dataflow
          .projects()
          .locations()
          .jobs()
          .get(projectId, jobRegion, jobId)
          .execute()
          .getCurrentState();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * A fake implementation of {@link RequestStatusChecker} for managing SQL-backed locks from
   * non-AppEngine platforms. This is only required until the Nomulus server is migrated off
   * AppEngine.
   */
  static class FakeRequestStatusChecker implements RequestStatusChecker {

    @Override
    public String getLogId() {
      return ValidateDatastoreWithSqlCommand.class.getSimpleName() + "-" + UUID.randomUUID();
    }

    @Override
    public boolean isRunning(String requestLogId) {
      return false;
    }
  }
}
