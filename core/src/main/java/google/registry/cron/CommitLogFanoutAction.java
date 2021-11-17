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

import com.google.common.collect.ImmutableMultimap;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.util.CloudTasksUtils;
import java.util.Optional;
import javax.inject.Inject;

/** Action for fanning out cron tasks for each commit log bucket. */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/cron/commitLogFanout",
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class CommitLogFanoutAction implements Runnable {

  public static final String BUCKET_PARAM = "bucket";

  @Inject Clock clock;
  @Inject CloudTasksUtils cloudTasksUtils;

  @Inject @Parameter("endpoint") String endpoint;
  @Inject @Parameter("queue") String queue;
  @Inject @Parameter("jitterSeconds") Optional<Integer> jitterSeconds;
  @Inject CommitLogFanoutAction() {}



  @Override
  public void run() {
    for (int bucketId : CommitLogBucket.getBucketIds()) {
      cloudTasksUtils.enqueue(
          queue,
          CloudTasksUtils.createPostTask(
              endpoint,
              Service.BACKEND.toString(),
              ImmutableMultimap.of(BUCKET_PARAM, Integer.toString(bucketId)),
              clock,
              jitterSeconds));
    }
  }
}
