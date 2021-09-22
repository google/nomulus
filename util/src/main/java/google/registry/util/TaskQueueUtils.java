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

package google.registry.util;

import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.TaskHandle;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TransientFailureException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.flogger.FluentLogger;
import java.io.Serializable;
import java.util.List;
import javax.inject.Inject;

/** Utilities for dealing with App Engine task queues. */
public class TaskQueueUtils implements Serializable {

  private static final long serialVersionUID = 7893211200220508362L;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Retrier retrier;

  @Inject
  public TaskQueueUtils(Retrier retrier) {
    this.retrier = retrier;
  }

  @NonFinalForTesting
  @VisibleForTesting
  static int BATCH_SIZE = 1000;

  /**
   * The batch size to use for App Engine task queue operations.
   *
   * <p>Note that 1,000 is currently the maximum allowable batch size in App Engine.
   */
  public static int getBatchSize() {
    return BATCH_SIZE;
  }

  /**
   * Adds a task to a App Engine task queue in a reliable manner.
   *
   * <p>This is the same as {@link Queue#add(TaskOptions)} except it'll automatically retry with
   * exponential backoff if {@link TransientFailureException} is thrown.
   *
   * @throws TransientFailureException if retrying failed for the maximum period of time, or an
   *     {@link InterruptedException} told us to stop trying
   * @return successfully enqueued task
   */
  public TaskHandle enqueue(Queue queue, TaskOptions task) {
    return enqueue(queue, ImmutableList.of(task)).get(0);
  }

  /**
   * Adds tasks to an App Engine task queue in a reliable manner.
   *
   * <p>This is the same as {@link Queue#add(Iterable)} except it'll automatically retry with
   * exponential backoff if {@link TransientFailureException} is thrown.
   *
   * @throws TransientFailureException if retrying failed for the maximum period of time, or an
   *     {@link InterruptedException} told us to stop trying
   * @return successfully enqueued tasks
   */
  public List<TaskHandle> enqueue(final Queue queue, final Iterable<TaskOptions> tasks) {
    return retrier.callWithRetry(
        () -> {
          for (TaskOptions task : tasks) {
            logger.atInfo().log(
                "Enqueuing queue='%s' endpoint='%s'.", queue.getQueueName(), task.getUrl());
          }
          return queue.add(tasks);
        },
        TransientFailureException.class);
  }

  /** Deletes the specified tasks from the queue in batches, with retrying. */
  public void deleteTasks(Queue queue, List<TaskHandle> tasks) {
    Lists.partition(tasks, BATCH_SIZE)
        .stream()
        .forEach(
            batch ->
                retrier.callWithRetry(
                    () -> queue.deleteTask(batch), TransientFailureException.class));
  }
}
