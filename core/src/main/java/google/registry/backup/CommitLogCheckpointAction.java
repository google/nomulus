// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static com.google.appengine.api.taskqueue.TaskOptions.Builder.withUrl;
import static google.registry.backup.ExportCommitLogDiffAction.LOWER_CHECKPOINT_TIME_PARAM;
import static google.registry.backup.ExportCommitLogDiffAction.UPPER_CHECKPOINT_TIME_PARAM;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;

import com.google.common.flogger.FluentLogger;
import google.registry.model.ofy.CommitLogCheckpoint;
import google.registry.model.ofy.CommitLogCheckpointRoot;
import google.registry.request.Action;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.util.TaskQueueUtils;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * Action that saves commit log checkpoints to Datastore and kicks off a diff export task.
 *
 * <p>We separate computing and saving the checkpoint from exporting it because the export to GCS is
 * retryable but should not require the computation of a new checkpoint. Saving the checkpoint and
 * enqueuing the export task are done transactionally, so any checkpoint that is saved will be
 * exported to GCS very soon.
 *
 * <p>This action's supported method is GET rather than POST because it gets invoked via cron.
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/cron/commitLogCheckpoint",
    method = Action.Method.GET,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class CommitLogCheckpointAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String QUEUE_NAME = "export-commits";

  @Inject Clock clock;
  @Inject CommitLogCheckpointStrategy strategy;
  @Inject TaskQueueUtils taskQueueUtils;
  @Inject CommitLogCheckpointAction() {}

  @Override
  public void run() {
    final CommitLogCheckpoint checkpoint = strategy.computeCheckpoint();
    logger.atInfo().log(
        "Generated candidate checkpoint for time: %s", checkpoint.getCheckpointTime());
    tm()
        .transact(
            () -> {
              DateTime lastWrittenTime = CommitLogCheckpointRoot.loadRoot().getLastWrittenTime();
              if (isBeforeOrAt(checkpoint.getCheckpointTime(), lastWrittenTime)) {
                logger.atInfo().log(
                    "Newer checkpoint already written at time: %s", lastWrittenTime);
                return;
              }
              ofy()
                  .saveWithoutBackup()
                  .entities(
                      checkpoint, CommitLogCheckpointRoot.create(checkpoint.getCheckpointTime()));
              // Enqueue a diff task between previous and current checkpoints.
              taskQueueUtils.enqueue(
                  getQueue(QUEUE_NAME),
                  withUrl(ExportCommitLogDiffAction.PATH)
                      .param(LOWER_CHECKPOINT_TIME_PARAM, lastWrittenTime.toString())
                      .param(
                          UPPER_CHECKPOINT_TIME_PARAM, checkpoint.getCheckpointTime().toString()));
            });
  }
}
