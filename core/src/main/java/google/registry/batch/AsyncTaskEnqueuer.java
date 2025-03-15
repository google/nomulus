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

package google.registry.batch;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;

import com.google.common.base.Joiner;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimap;
import com.google.common.flogger.FluentLogger;
import google.registry.model.EppResource;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import jakarta.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** Helper class to enqueue tasks for handling asynchronous operations in flows. */
public final class AsyncTaskEnqueuer {

  /** The HTTP parameter names used by async flows. */
  public static final String PARAM_RESOURCE_KEY = "resourceKey";
  public static final String PARAM_REQUESTED_TIME = "requestedTime";
  public static final String PARAM_RESAVE_TIMES = "resaveTimes";

  /** The task queue names used by async flows. */
  public static final String QUEUE_ASYNC_ACTIONS = "async-actions";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Duration MAX_ASYNC_ETA = Duration.standardDays(30);

  private final CloudTasksUtils cloudTasksUtils;

  @Inject
  public AsyncTaskEnqueuer(CloudTasksUtils cloudTasksUtils) {
    this.cloudTasksUtils = cloudTasksUtils;
  }

  /**
   * Enqueues a task to asynchronously re-save an entity at some point(s) in the future.
   *
   * <p>Multiple re-save times are chained one after the other, i.e. any given run will re-enqueue
   * itself to run at the next time if there are remaining re-saves scheduled.
   */
  public void enqueueAsyncResave(
      VKey<? extends EppResource> entityKey,
      DateTime now,
      ImmutableSortedSet<DateTime> whenToResave) {
    DateTime firstResave = whenToResave.first();
    checkArgument(isBeforeOrAt(now, firstResave), "Can't enqueue a resave to run in the past");
    Duration etaDuration = new Duration(now, firstResave);
    if (etaDuration.isLongerThan(MAX_ASYNC_ETA)) {
      logger.atInfo().log(
          "Ignoring async re-save of %s; %s is past the ETA threshold of %s.",
          entityKey, firstResave, MAX_ASYNC_ETA);
      return;
    }
    Multimap<String, String> params = ArrayListMultimap.create();
    params.put(PARAM_RESOURCE_KEY, entityKey.stringify());
    params.put(PARAM_REQUESTED_TIME, now.toString());
    if (whenToResave.size() > 1) {
      params.put(PARAM_RESAVE_TIMES, Joiner.on(',').join(whenToResave.tailSet(firstResave, false)));
    }
    logger.atInfo().log("Enqueuing async re-save of %s to run at %s.", entityKey, whenToResave);
    cloudTasksUtils.enqueue(
        QUEUE_ASYNC_ACTIONS,
        cloudTasksUtils.createTaskWithDelay(
            ResaveEntityAction.class, Action.Method.POST, params, etaDuration));
  }
}
