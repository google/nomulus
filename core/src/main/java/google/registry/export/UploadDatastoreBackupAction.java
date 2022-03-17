// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.export;

import static com.google.common.base.MoreObjects.firstNonNull;
import static google.registry.request.Action.Method.POST;

import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.model.Job;
import com.google.api.services.bigquery.model.JobConfiguration;
import com.google.api.services.bigquery.model.JobConfigurationLoad;
import com.google.api.services.bigquery.model.JobReference;
import com.google.api.services.bigquery.model.TableReference;
import com.google.cloud.tasks.v2.Task;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.protobuf.ByteString;
import com.google.protobuf.util.Timestamps;
import google.registry.bigquery.BigqueryUtils.SourceFormat;
import google.registry.bigquery.BigqueryUtils.WriteDisposition;
import google.registry.bigquery.CheckedBigquery;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.InternalServerErrorException;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.util.CloudTasksUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import javax.inject.Inject;

/** Action to load a Datastore backup from Google Cloud Storage into BigQuery. */
@Action(
    service = Action.Service.BACKEND,
    path = UploadDatastoreBackupAction.PATH,
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
@DeleteAfterMigration
public class UploadDatastoreBackupAction implements Runnable {

  /** Parameter names for passing parameters into the servlet. */
  static final String UPLOAD_BACKUP_ID_PARAM = "id";

  static final String UPLOAD_BACKUP_FOLDER_PARAM = "folder";
  static final String UPLOAD_BACKUP_KINDS_PARAM = "kinds";

  static final String BACKUP_DATASET = "datastore_backups";

  /** Servlet-specific details needed for enqueuing tasks against itself. */
  static final String QUEUE = "export-snapshot";  // See queue.xml.

  static final String LATEST_BACKUP_VIEW_NAME = "latest_datastore_export";

  static final String PATH = "/_dr/task/uploadDatastoreBackup"; // See web.xml.

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject CheckedBigquery checkedBigquery;
  @Inject CloudTasksUtils cloudTasksUtils;
  @Inject Clock clock;

  @Inject @Config("projectId") String projectId;

  @Inject
  @Parameter(UPLOAD_BACKUP_FOLDER_PARAM)
  String backupFolderUrl;

  @Inject
  @Parameter(UPLOAD_BACKUP_ID_PARAM)
  String backupId;

  @Inject
  @Parameter(UPLOAD_BACKUP_KINDS_PARAM)
  String backupKinds;

  @Inject
  UploadDatastoreBackupAction() {}

  @Override
  public void run() {
    try {
      String message = uploadBackup(backupId, backupFolderUrl, Splitter.on(',').split(backupKinds));
      logger.atInfo().log("Loaded backup successfully: %s", message);
    } catch (Throwable e) {
      logger.atSevere().withCause(e).log("Error loading backup.");
      if (e instanceof IllegalArgumentException) {
        throw new BadRequestException("Error calling load backup: " + e.getMessage(), e);
      } else {
        throw new InternalServerErrorException(
            "Error loading backup: " + firstNonNull(e.getMessage(), e.toString()));
      }
    }
  }

  private String uploadBackup(String backupId, String backupFolderUrl, Iterable<String> kinds)
      throws IOException {
    Bigquery bigquery = checkedBigquery.ensureDataSetExists(projectId, BACKUP_DATASET);
    String loadMessage =
        String.format("Loading Datastore backup %s from %s...", backupId, backupFolderUrl);
    logger.atInfo().log(loadMessage);

    String sanitizedBackupId = sanitizeForBigquery(backupId);
    StringBuilder builder = new StringBuilder(loadMessage + "\n");
    builder.append("Load jobs:\n");

    for (String kindName : kinds) {
      String jobId = String.format("load-backup-%s-%s", sanitizedBackupId, kindName);
      JobReference jobRef = new JobReference().setProjectId(projectId).setJobId(jobId);
      String sourceUri = getBackupInfoFileForKind(backupFolderUrl, kindName);
      String tableId = String.format("%s_%s", sanitizedBackupId, kindName);

      // Launch the load job.
      Job job = makeLoadJob(jobRef, sourceUri, tableId);
      bigquery.jobs().insert(projectId, job).execute();

      // Serialize the chainedTask into a byte array to put in the task payload.
      ByteArrayOutputStream taskBytes = new ByteArrayOutputStream();
      new ObjectOutputStream(taskBytes)
          .writeObject(
              cloudTasksUtils.createPostTask(
                  UpdateSnapshotViewAction.PATH,
                  Service.BACKEND.toString(),
                  ImmutableMultimap.of(
                      UpdateSnapshotViewAction.UPDATE_SNAPSHOT_DATASET_ID_PARAM,
                      BACKUP_DATASET,
                      UpdateSnapshotViewAction.UPDATE_SNAPSHOT_TABLE_ID_PARAM,
                      tableId,
                      UpdateSnapshotViewAction.UPDATE_SNAPSHOT_KIND_PARAM,
                      kindName,
                      UpdateSnapshotViewAction.UPDATE_SNAPSHOT_VIEWNAME_PARAM,
                      LATEST_BACKUP_VIEW_NAME)));

      // Enqueues a task to poll for the success or failure of the referenced BigQuery job and to
      // launch the provided task in the specified queue if the job succeeds.
      cloudTasksUtils.enqueue(
          BigqueryPollJobAction.QUEUE,
          Task.newBuilder()
              .setAppEngineHttpRequest(
                  cloudTasksUtils
                      .createPostTask(BigqueryPollJobAction.PATH, Service.BACKEND.toString(), null)
                      .getAppEngineHttpRequest()
                      .toBuilder()
                      .putHeaders(BigqueryPollJobAction.PROJECT_ID_HEADER, jobRef.getProjectId())
                      .putHeaders(BigqueryPollJobAction.JOB_ID_HEADER, jobRef.getJobId())
                      .putHeaders(
                          BigqueryPollJobAction.CHAINED_TASK_QUEUE_HEADER,
                          UpdateSnapshotViewAction.QUEUE)
                      // need to include CONTENT_TYPE in header when body is not empty
                      .putHeaders(HttpHeaders.CONTENT_TYPE, MediaType.FORM_DATA.toString())
                      .setBody(ByteString.copyFrom(taskBytes.toByteArray()))
                      .build())
              .setScheduleTime(
                  Timestamps.fromMillis(
                      clock.nowUtc().plus(BigqueryPollJobAction.POLL_COUNTDOWN).getMillis()))
              .build());

      builder.append(String.format(" - %s:%s\n", projectId, jobId));
      logger.atInfo().log("Submitted load job %s:%s.", projectId, jobId);
    }
    return builder.toString();
  }

  private static String sanitizeForBigquery(String backupId) {
    return backupId.replaceAll("[^a-zA-Z0-9_]", "_");
  }

  @VisibleForTesting
  static String getBackupInfoFileForKind(String backupFolderUrl, String kindName) {
    return Joiner.on('/')
        .join(
            backupFolderUrl,
            "all_namespaces",
            String.format("kind_%s", kindName),
            String.format("all_namespaces_kind_%s.%s", kindName, "export_metadata"));
  }

  private Job makeLoadJob(JobReference jobRef, String sourceUri, String tableId) {
    TableReference tableReference =
        new TableReference()
            .setProjectId(jobRef.getProjectId())
            .setDatasetId(BACKUP_DATASET)
            .setTableId(tableId);
    return new Job()
        .setJobReference(jobRef)
        .setConfiguration(new JobConfiguration()
            .setLoad(new JobConfigurationLoad()
                .setWriteDisposition(WriteDisposition.WRITE_EMPTY.toString())
                .setSourceFormat(SourceFormat.DATASTORE_BACKUP.toString())
                .setSourceUris(ImmutableList.of(sourceUri))
                .setDestinationTable(tableReference)));
  }
}
