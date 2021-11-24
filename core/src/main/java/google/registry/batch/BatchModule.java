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

import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_REQUESTED_TIME;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_RESAVE_TIMES;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_RESOURCE_KEY;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_ACTIONS;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_DELETE;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_HOST_RENAME;
import static google.registry.request.RequestParameters.extractIntParameter;
import static google.registry.request.RequestParameters.extractLongParameter;
import static google.registry.request.RequestParameters.extractOptionalBooleanParameter;
import static google.registry.request.RequestParameters.extractOptionalDatetimeParameter;
import static google.registry.request.RequestParameters.extractOptionalIntParameter;
import static google.registry.request.RequestParameters.extractOptionalParameter;
import static google.registry.request.RequestParameters.extractRequiredDatetimeParameter;
import static google.registry.request.RequestParameters.extractRequiredParameter;
import static google.registry.request.RequestParameters.extractSetOfDatetimeParameters;

import com.google.appengine.api.taskqueue.Queue;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import dagger.Module;
import dagger.Provides;
import google.registry.model.ImmutableObject;
import google.registry.request.Parameter;
import java.util.Optional;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.DateTime;

/** Dagger module for injecting common settings for batch actions. */
@Module
public class BatchModule {

  @Provides
  @Parameter("jobName")
  static Optional<String> provideJobName(HttpServletRequest req) {
    return extractOptionalParameter(req, "jobName");
  }

  @Provides
  @Parameter("jobId")
  static Optional<String> provideJobId(HttpServletRequest req) {
    return extractOptionalParameter(req, "jobId");
  }

  @Provides
  @Parameter("numJobsToDelete")
  static Optional<Integer> provideNumJobsToDelete(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "numJobsToDelete");
  }

  @Provides
  @Parameter("daysOld")
  static Optional<Integer> provideDaysOld(HttpServletRequest req) {
    return extractOptionalIntParameter(req, "daysOld");
  }

  @Provides
  @Parameter("force")
  static Optional<Boolean> provideForce(HttpServletRequest req) {
    return extractOptionalBooleanParameter(req, "force");
  }

  @Provides
  @Parameter(PARAM_RESOURCE_KEY)
  // TODO(b/207363014): figure out if this needs to be modified for vkey string replacement
  static Key<ImmutableObject> provideResourceKey(HttpServletRequest req) {
    return Key.create(extractRequiredParameter(req, PARAM_RESOURCE_KEY));
  }

  @Provides
  @Parameter(PARAM_REQUESTED_TIME)
  static DateTime provideRequestedTime(HttpServletRequest req) {
    return extractRequiredDatetimeParameter(req, PARAM_REQUESTED_TIME);
  }

  @Provides
  @Parameter(PARAM_RESAVE_TIMES)
  static ImmutableSet<DateTime> provideResaveTimes(HttpServletRequest req) {
    return extractSetOfDatetimeParameters(req, PARAM_RESAVE_TIMES);
  }

  @Provides
  @Parameter(RelockDomainAction.OLD_UNLOCK_REVISION_ID_PARAM)
  static long provideOldUnlockRevisionId(HttpServletRequest req) {
    return extractLongParameter(req, RelockDomainAction.OLD_UNLOCK_REVISION_ID_PARAM);
  }

  @Provides
  @Parameter(RelockDomainAction.PREVIOUS_ATTEMPTS_PARAM)
  static int providePreviousAttempts(HttpServletRequest req) {
    return extractIntParameter(req, RelockDomainAction.PREVIOUS_ATTEMPTS_PARAM);
  }

  @Provides
  @Parameter(ExpandRecurringBillingEventsAction.PARAM_CURSOR_TIME)
  static Optional<DateTime> provideCursorTime(HttpServletRequest req) {
    return extractOptionalDatetimeParameter(
        req, ExpandRecurringBillingEventsAction.PARAM_CURSOR_TIME);
  }

  @Provides
  @Named(QUEUE_ASYNC_ACTIONS)
  static Queue provideAsyncActionsPushQueue() {
    return getQueue(QUEUE_ASYNC_ACTIONS);
  }

  @Provides
  @Named(QUEUE_ASYNC_DELETE)
  static Queue provideAsyncDeletePullQueue() {
    return getQueue(QUEUE_ASYNC_DELETE);
  }

  @Provides
  @Named(QUEUE_ASYNC_HOST_RENAME)
  static Queue provideAsyncHostRenamePullQueue() {
    return getQueue(QUEUE_ASYNC_HOST_RENAME);
  }
}
