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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Sets.intersection;
import static google.registry.export.UploadDatastoreBackupAction.enqueueUploadBackupTask;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskHandle;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TaskOptions.Method;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.flogger.FluentLogger;
import google.registry.export.datastore.DatastoreAdmin;
import google.registry.export.datastore.Operation;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.request.Action;
import google.registry.request.HttpException;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.InternalServerErrorException;
import google.registry.request.HttpException.NoContentException;
import google.registry.request.HttpException.NotModifiedException;
import google.registry.request.Parameter;
import google.registry.request.RequestMethod;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import java.io.IOException;
import java.util.Set;
import javax.inject.Inject;
import org.joda.time.Duration;
import org.joda.time.PeriodType;
import org.joda.time.format.PeriodFormat;

/**
 * Action that checks the status of a snapshot, and if complete, trigger loading it into BigQuery.
 */
@Action(
    service = Action.Service.BACKEND,
    path = CheckBackupAction.PATH,
    method = {POST, GET},
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
@DeleteAfterMigration
public class CheckBackupAction implements Runnable {

  /** Parameter names for passing parameters into this action. */
  static final String CHECK_BACKUP_NAME_PARAM = "name";

  static final String CHECK_BACKUP_KINDS_TO_LOAD_PARAM = "kindsToLoad";

  /** Action-specific details needed for enqueuing tasks against itself. */
  static final String QUEUE = "export-snapshot-poll"; // See queue.xml.

  static final String PATH = "/_dr/task/checkDatastoreBackup"; // See web.xml.
  static final Duration POLL_COUNTDOWN = Duration.standardMinutes(2);

  /** The maximum amount of time we allow a backup to run before abandoning it. */
  static final Duration MAXIMUM_BACKUP_RUNNING_TIME = Duration.standardHours(20);

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject DatastoreAdmin datastoreAdmin;
  @Inject Clock clock;
  @Inject Response response;
  @Inject @RequestMethod Action.Method requestMethod;

  @Inject
  @Parameter(CHECK_BACKUP_NAME_PARAM)
  String backupName;

  @Inject
  @Parameter(CHECK_BACKUP_KINDS_TO_LOAD_PARAM)
  String kindsToLoadParam;

  @Inject
  CheckBackupAction() {}

  @Override
  public void run() {
    try {
    if (requestMethod == POST) {
        checkAndLoadBackupIfComplete();
    } else {
        // This is a GET request.
        // TODO(weiminyu): consider moving this functionality to Registry tool.
        response.setPayload(getExportStatus().toPrettyString());
      }
    } catch (HttpException e) {
      // Rethrow and let caller propagate status code and error message to the response.
      // See google.registry.request.RequestHandler#handleRequest.
      throw e;
    } catch (Throwable e) {
      throw new InternalServerErrorException(
          "Exception occurred while checking datastore exports.", e);
    }
  }

  private Operation getExportStatus() throws IOException {
    try {
      return datastoreAdmin.get(backupName).execute();
    } catch (GoogleJsonResponseException e) {
      if (e.getStatusCode() == SC_NOT_FOUND) {
        String message = String.format("Bad backup name %s: %s", backupName, e.getMessage());
        // TODO(b/19081569): Ideally this would return a 2XX error so the task would not be
        // retried but we might abandon backups that start late and haven't yet written to
        // Datastore. We could fix that by replacing this with a two-phase polling strategy.
        throw new BadRequestException(message, e);
      }
      throw e;
    }
  }

  private void checkAndLoadBackupIfComplete() throws IOException {
    Set<String> kindsToLoad = ImmutableSet.copyOf(Splitter.on(',').split(kindsToLoadParam));
    Operation backup = getExportStatus();

    checkArgument(backup.isExport(), "Expecting an export operation: [%s].", backupName);

    if (backup.isProcessing()
        && backup.getRunningTime(clock).isShorterThan(MAXIMUM_BACKUP_RUNNING_TIME)) {
      // Backup might still be running, so send a 304 to have the task retry.
      throw new NotModifiedException(
          String.format(
              "Datastore backup %s still in progress: %s", backupName, backup.getProgress()));
    }
    if (!backup.isSuccessful()) {
      // Declare the backup a lost cause, and send 204 No Content so the task will
      // not be retried.
      String message =
          String.format(
              "Datastore backup %s abandoned - not complete after %s. Progress: %s",
              backupName,
              PeriodFormat.getDefault()
                  .print(
                      backup
                          .getRunningTime(clock)
                          .toPeriod()
                          .normalizedStandard(PeriodType.dayTime().withMillisRemoved())),
              backup.getProgress());
      throw new NoContentException(message);
    }

    String backupId = backup.getExportId();
    // Log a warning if kindsToLoad is not a subset of the exported kinds.
    if (!backup.getKinds().containsAll(kindsToLoad)) {
      logger.atWarning().log(
          "Kinds to load included non-exported kinds: %s",
          Sets.difference(kindsToLoad, backup.getKinds()));
    }
    // Load kinds from the backup, limited to those also in kindsToLoad (if it's present).
    ImmutableSet<String> exportedKindsToLoad =
        ImmutableSet.copyOf(intersection(backup.getKinds(), kindsToLoad));
    String message = String.format("Datastore backup %s complete - ", backupName);
    if (exportedKindsToLoad.isEmpty()) {
      message += "no kinds to load into BigQuery.";
    } else {
      enqueueUploadBackupTask(backupId, backup.getExportFolderUrl(), exportedKindsToLoad);
      message += "BigQuery load task enqueued.";
    }
    logger.atInfo().log(message);
    response.setPayload(message);
  }

  /** Enqueue a poll task to monitor the named backup for completion. */
  static TaskHandle enqueuePollTask(String backupId, ImmutableSet<String> kindsToLoad) {
    return QueueFactory.getQueue(QUEUE)
        .add(
            TaskOptions.Builder.withUrl(PATH)
                .method(Method.POST)
                .countdownMillis(POLL_COUNTDOWN.getMillis())
                .param(CHECK_BACKUP_NAME_PARAM, backupId)
                .param(CHECK_BACKUP_KINDS_TO_LOAD_PARAM, Joiner.on(',').join(kindsToLoad)));
  }
}
