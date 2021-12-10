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

import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static com.google.common.base.MoreObjects.firstNonNull;
import static google.registry.export.UpdateSnapshotViewAction.createViewUpdateTask;
import static google.registry.request.Action.Method.POST;

import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.model.Job;
import com.google.api.services.bigquery.model.JobConfiguration;
import com.google.api.services.bigquery.model.JobConfigurationLoad;
import com.google.api.services.bigquery.model.JobReference;
import com.google.api.services.bigquery.model.TableReference;
import com.google.appengine.api.taskqueue.TaskHandle;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TaskOptions.Method;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.bigquery.BigqueryUtils.SourceFormat;
import google.registry.bigquery.BigqueryUtils.WriteDisposition;
import google.registry.bigquery.CheckedBigquery;
import google.registry.config.RegistryConfig.Config;
import google.registry.export.BigqueryPollJobAction.BigqueryPollJobEnqueuer;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.request.Action;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.InternalServerErrorException;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import java.io.IOException;
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
  @Inject BigqueryPollJobEnqueuer bigqueryPollEnqueuer;
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

  /** Enqueue a task for starting a backup load. */
  public static TaskHandle enqueueUploadBackupTask(
      String backupId, String gcsFile, ImmutableSet<String> kinds) {
    return getQueue(QUEUE)
        .add(
            TaskOptions.Builder.withUrl(PATH)
                .method(Method.POST)
                .param(UPLOAD_BACKUP_ID_PARAM, backupId)
                .param(UPLOAD_BACKUP_FOLDER_PARAM, gcsFile)
                .param(UPLOAD_BACKUP_KINDS_PARAM, Joiner.on(',').join(kinds)));
  }

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

      // Enqueue a task to check on the load job's completion, and if it succeeds, to update a
      // well-known view in BigQuery to point at the newly loaded backup table for this kind.
      bigqueryPollEnqueuer.enqueuePollTask(
          jobRef,
          createViewUpdateTask(BACKUP_DATASET, tableId, kindName, LATEST_BACKUP_VIEW_NAME),
          getQueue(UpdateSnapshotViewAction.QUEUE));

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
