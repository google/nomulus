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

import static google.registry.export.CheckBackupAction.enqueuePollTask;
import static google.registry.request.Action.Method.POST;

import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig;
import google.registry.export.datastore.DatastoreAdmin;
import google.registry.export.datastore.Operation;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.request.Action;
import google.registry.request.HttpException.InternalServerErrorException;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import javax.inject.Inject;

/**
 * Action to trigger a Datastore backup job that writes a snapshot to Google Cloud Storage.
 *
 * <p>This is the first step of a four step workflow for exporting snapshots, with each step calling
 * the next upon successful completion:
 *
 * <ol>
 *   <li>The snapshot is exported to Google Cloud Storage (this action).
 *   <li>The {@link CheckBackupAction} polls until the export is completed.
 *   <li>The {@link UploadDatastoreBackupAction} uploads the data from GCS to BigQuery.
 *   <li>The {@link UpdateSnapshotViewAction} updates the view in latest_datastore_export.
 * </ol>
 */
@Action(
    service = Action.Service.BACKEND,
    path = BackupDatastoreAction.PATH,
    method = POST,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
@DeleteAfterMigration
public class BackupDatastoreAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Queue to use for enqueuing the task that will actually launch the backup. */
  static final String QUEUE = "export-snapshot"; // See queue.xml.

  static final String PATH = "/_dr/task/backupDatastore"; // See web.xml.

  @Inject DatastoreAdmin datastoreAdmin;
  @Inject Response response;

  @Inject
  BackupDatastoreAction() {}

  @Override
  public void run() {
    try {
      Operation backup =
          datastoreAdmin
              .export(
                  RegistryConfig.getDatastoreBackupsBucket(), AnnotatedEntities.getBackupKinds())
              .execute();

      String backupName = backup.getName();
      // Enqueue a poll task to monitor the backup and load REPORTING-related kinds into bigquery.
      enqueuePollTask(backupName, AnnotatedEntities.getReportingKinds());
      String message =
          String.format(
              "Datastore backup started with name: %s\nSaving to %s",
              backupName, backup.getExportFolderUrl());
      logger.atInfo().log(message);
      response.setPayload(message);
    } catch (Throwable e) {
      throw new InternalServerErrorException("Exception occurred while backing up Datastore", e);
    }
  }
}
