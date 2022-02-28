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

import com.beust.jcommander.Parameter;
import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dataflow.model.Job;
import com.google.api.services.dataflow.model.LaunchFlexTemplateParameter;
import com.google.api.services.dataflow.model.LaunchFlexTemplateRequest;
import com.google.api.services.dataflow.model.LaunchFlexTemplateResponse;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.beam.common.DatabaseSnapshot;
import google.registry.config.RegistryConfig.Config;
import google.registry.tools.params.DateTimeParameter;
import google.registry.util.Clock;
import google.registry.util.RequestStatusChecker;
import google.registry.util.Sleeper;
import java.io.IOException;
import java.util.UUID;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** Shared setup for commands that validate the data replication between Datastore and Cloud SQL. */
abstract class ValidateDatabaseMigrationCommand
    implements CommandWithConnection, CommandWithRemoteApi {

  private static final String PIPELINE_NAME = "validate_database_pipeline";

  private static final String MANUAL_PIPELINE_LAUNCH_COMMAND_TEMPLATE =
      "gcloud dataflow flex-template run "
          + "\"%s-${USER}-$(date +%%Y%%m%%dt%%H%%M%%S)\" "
          + "--template-file-gcs-location %s "
          + "--project %s "
          + "--region=%s "
          + "--worker-machine-type=n2-standard-8 --num-workers=8 "
          + "--parameters registryEnvironment=%s "
          + "--parameters sqlSnapshotId=%s "
          + "--parameters latestCommitLogTimestamp=%s ";

  // States indicating a job is not finished yet.
  static final ImmutableSet<String> DATAFLOW_JOB_RUNNING_STATES =
      ImmutableSet.of(
          "JOB_STATE_UNKNOWN",
          "JOB_STATE_RUNNING",
          "JOB_STATE_STOPPED",
          "JOB_STATE_PENDING",
          "JOB_STATE_QUEUED");

  static final Duration JOB_POLLING_INTERVAL = Duration.standardSeconds(60);

  @Parameter(
      names = {"-m", "--manual"},
      description =
          "If true, let user launch the comparison pipeline manually out of band. "
              + "Command will wait for user key-press to exit after syncing Datastore.")
  boolean manualLaunchPipeline;

  @Parameter(
      names = {"-r", "--release"},
      description = "The release tag of the BEAM pipeline to run. It defaults to 'live'.")
  String release = "live";

  @Parameter(
      names = {"-c", "--comparisonStartTimestamp"},
      description =
          "When comparing History and Epp Resource entities, ignore those that have not"
              + " changed since this time.",
      converter = DateTimeParameter.class)
  DateTime comparisonStartTimestamp;

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

  AppEngineConnection connection;

  @Override
  public void setConnection(AppEngineConnection connection) {
    this.connection = connection;
  }

  String getDataflowJobStatus(String jobId) {
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

  void launchPipelineAndWaitUntilFinish(
      String pipelineName, DatabaseSnapshot snapshot, String latestCommitTimestamp) {
    Job pipelineJob =
        launchComparisonPipeline(pipelineName, snapshot.getSnapshotId(), latestCommitTimestamp)
            .getJob();
    String jobId = pipelineJob.getId();

    System.out.printf("Launched comparison pipeline %s (%s).\n", pipelineJob.getName(), jobId);

    while (DATAFLOW_JOB_RUNNING_STATES.contains(getDataflowJobStatus(jobId))) {
      sleeper.sleepInterruptibly(JOB_POLLING_INTERVAL);
    }
    System.out.printf(
        "Pipeline ended with %s state. Please check counters for results.\n",
        getDataflowJobStatus(jobId));
  }

  String getContainerSpecGcsPath() {
    return String.format(
        "%s/%s_metadata.json", stagingBucketUrl.replace("live", release), PIPELINE_NAME);
  }

  String getManualLaunchCommand(
      String jobName, String snapshotId, String latestCommitLogTimestamp) {
    String baseCommand =
        String.format(
            MANUAL_PIPELINE_LAUNCH_COMMAND_TEMPLATE,
            jobName,
            getContainerSpecGcsPath(),
            projectId,
            jobRegion,
            RegistryToolEnvironment.get().name(),
            snapshotId,
            latestCommitLogTimestamp);
    if (comparisonStartTimestamp == null) {
      return baseCommand;
    }
    return baseCommand + "--parameters comparisonStartTimestamp=" + comparisonStartTimestamp;
  }

  LaunchFlexTemplateResponse launchComparisonPipeline(
      String jobName, String sqlSnapshotId, String latestCommitLogTimestamp) {
    try {
      // Hardcode machine type and initial workers to force a quick start.
      ImmutableMap.Builder<String, String> paramsBuilder =
          new ImmutableMap.Builder()
              .put("workerMachineType", "n2-standard-8")
              .put("numWorkers", "8")
              .put("sqlSnapshotId", sqlSnapshotId)
              .put("latestCommitLogTimestamp", latestCommitLogTimestamp)
              .put("registryEnvironment", RegistryToolEnvironment.get().name());
      if (comparisonStartTimestamp != null) {
        paramsBuilder.put("comparisonStartTimestamp", comparisonStartTimestamp.toString());
      }
      LaunchFlexTemplateParameter parameter =
          new LaunchFlexTemplateParameter()
              .setJobName(createJobName(Ascii.toLowerCase(jobName).replace('_', '-'), clock))
              .setContainerSpecGcsPath(getContainerSpecGcsPath())
              .setParameters(paramsBuilder.build());
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

  /**
   * A fake implementation of {@link RequestStatusChecker} for managing SQL-backed locks from
   * non-AppEngine platforms. This is only required until the Nomulus server is migrated off
   * AppEngine.
   */
  static class FakeRequestStatusChecker implements RequestStatusChecker {

    private final String logId =
        ValidateDatastoreCommand.class.getSimpleName() + "-" + UUID.randomUUID();

    @Override
    public String getLogId() {
      return logId;
    }

    @Override
    public boolean isRunning(String requestLogId) {
      return false;
    }
  }
}
