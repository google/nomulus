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

package google.registry.cron;

import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;

import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.TaskOptions;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.util.TaskQueueUtils;
import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import javax.inject.Inject;

/** Action for fanning out cron tasks for each commit log bucket. */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/cron/commitLogFanout",
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class CommitLogFanoutAction implements Runnable {

  public static final String BUCKET_PARAM = "bucket";

  private static final Random random = new Random();

  @Inject TaskQueueUtils taskQueueUtils;
  @Inject @Parameter("endpoint") String endpoint;
  @Inject @Parameter("queue") String queue;
  @Inject @Parameter("jitterSeconds") Optional<Integer> jitterSeconds;
  @Inject CommitLogFanoutAction() {}

  @Override
  public void run() {
    Queue taskQueue = getQueue(queue);
    for (int bucketId : CommitLogBucket.getBucketIds()) {
      long delay =
          jitterSeconds.map(i -> random.nextInt((int) Duration.ofSeconds(i).toMillis())).orElse(0);
      TaskOptions taskOptions =
          TaskOptions.Builder.withUrl(endpoint)
              .param(BUCKET_PARAM, Integer.toString(bucketId))
              .countdownMillis(delay);
      taskQueueUtils.enqueue(taskQueue, taskOptions);
    }
  }
}
