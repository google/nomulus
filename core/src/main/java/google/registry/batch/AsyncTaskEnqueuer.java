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

import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TaskOptions.Method;
import com.google.appengine.api.taskqueue.TransientFailureException;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.flogger.FluentLogger;
import com.googlecode.objectify.Key;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.persistence.VKey;
import google.registry.schema.domain.RegistryLock;
import google.registry.util.AppEngineServiceUtils;
import google.registry.util.Retrier;
import javax.inject.Inject;
import javax.inject.Named;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** Helper class to enqueue tasks for handling asynchronous operations in flows. */
public final class AsyncTaskEnqueuer {

  /** The HTTP parameter names used by async flows. */
  public static final String PARAM_RESOURCE_KEY = "resourceKey";
  public static final String PARAM_REQUESTING_CLIENT_ID = "requestingClientId";
  public static final String PARAM_CLIENT_TRANSACTION_ID = "clientTransactionId";
  public static final String PARAM_SERVER_TRANSACTION_ID = "serverTransactionId";
  public static final String PARAM_IS_SUPERUSER = "isSuperuser";
  public static final String PARAM_HOST_KEY = "hostKey";
  public static final String PARAM_REQUESTED_TIME = "requestedTime";
  public static final String PARAM_RESAVE_TIMES = "resaveTimes";

  /** The task queue names used by async flows. */
  public static final String QUEUE_ASYNC_ACTIONS = "async-actions";
  public static final String QUEUE_ASYNC_DELETE = "async-delete-pull";
  public static final String QUEUE_ASYNC_HOST_RENAME = "async-host-rename-pull";

  public static final String PATH_RESAVE_ENTITY = "/_dr/task/resaveEntity";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Duration MAX_ASYNC_ETA = Duration.standardDays(30);

  private final Duration asyncDeleteDelay;
  private final Queue asyncActionsPushQueue;
  private final Queue asyncDeletePullQueue;
  private final Queue asyncDnsRefreshPullQueue;
  private final AppEngineServiceUtils appEngineServiceUtils;
  private final Retrier retrier;

  @Inject
  public AsyncTaskEnqueuer(
      @Named(QUEUE_ASYNC_ACTIONS) Queue asyncActionsPushQueue,
      @Named(QUEUE_ASYNC_DELETE) Queue asyncDeletePullQueue,
      @Named(QUEUE_ASYNC_HOST_RENAME) Queue asyncDnsRefreshPullQueue,
      @Config("asyncDeleteFlowMapreduceDelay") Duration asyncDeleteDelay,
      AppEngineServiceUtils appEngineServiceUtils,
      Retrier retrier) {
    this.asyncActionsPushQueue = asyncActionsPushQueue;
    this.asyncDeletePullQueue = asyncDeletePullQueue;
    this.asyncDnsRefreshPullQueue = asyncDnsRefreshPullQueue;
    this.asyncDeleteDelay = asyncDeleteDelay;
    this.appEngineServiceUtils = appEngineServiceUtils;
    this.retrier = retrier;
  }

  /** Enqueues a task to asynchronously re-save an entity at some point in the future. */
  public void enqueueAsyncResave(
      ImmutableObject entityToResave, DateTime now, DateTime whenToResave) {
    enqueueAsyncResave(entityToResave, now, ImmutableSortedSet.of(whenToResave));
  }

  /**
   * Enqueues a task to asynchronously re-save an entity at some point(s) in the future.
   *
   * <p>Multiple re-save times are chained one after the other, i.e. any given run will re-enqueue
   * itself to run at the next time if there are remaining re-saves scheduled.
   */
  public void enqueueAsyncResave(
      ImmutableObject entityToResave, DateTime now, ImmutableSortedSet<DateTime> whenToResave) {
    DateTime firstResave = whenToResave.first();
    checkArgument(isBeforeOrAt(now, firstResave), "Can't enqueue a resave to run in the past");
    Key<ImmutableObject> entityKey = Key.create(entityToResave);
    Duration etaDuration = new Duration(now, firstResave);
    if (etaDuration.isLongerThan(MAX_ASYNC_ETA)) {
      logger.atInfo().log(
          "Ignoring async re-save of %s; %s is past the ETA threshold of %s.",
          entityKey, firstResave, MAX_ASYNC_ETA);
      return;
    }
    logger.atInfo().log("Enqueuing async re-save of %s to run at %s.", entityKey, whenToResave);
    String backendHostname = appEngineServiceUtils.getServiceHostname("backend");
    TaskOptions task =
        TaskOptions.Builder.withUrl(PATH_RESAVE_ENTITY)
            .method(Method.POST)
            .header("Host", backendHostname)
            .countdownMillis(etaDuration.getMillis())
            .param(PARAM_RESOURCE_KEY, entityKey.getString())
            .param(PARAM_REQUESTED_TIME, now.toString());
    if (whenToResave.size() > 1) {
      task.param(PARAM_RESAVE_TIMES, Joiner.on(',').join(whenToResave.tailSet(firstResave, false)));
    }
    addTaskToQueueWithRetry(asyncActionsPushQueue, task);
  }

  /** Enqueues a task to asynchronously delete a contact or host, by key. */
  public void enqueueAsyncDelete(
      EppResource resourceToDelete,
      DateTime now,
      String requestingClientId,
      Trid trid,
      boolean isSuperuser) {
    Key<EppResource> resourceKey = Key.create(resourceToDelete);
    logger.atInfo().log(
        "Enqueuing async deletion of %s on behalf of registrar %s.",
        resourceKey, requestingClientId);
    TaskOptions task =
        TaskOptions.Builder.withMethod(Method.PULL)
            .countdownMillis(asyncDeleteDelay.getMillis())
            .param(PARAM_RESOURCE_KEY, resourceKey.getString())
            .param(PARAM_REQUESTING_CLIENT_ID, requestingClientId)
            .param(PARAM_SERVER_TRANSACTION_ID, trid.getServerTransactionId())
            .param(PARAM_IS_SUPERUSER, Boolean.toString(isSuperuser))
            .param(PARAM_REQUESTED_TIME, now.toString());
    trid.getClientTransactionId()
        .ifPresent(clTrid -> task.param(PARAM_CLIENT_TRANSACTION_ID, clTrid));
    addTaskToQueueWithRetry(asyncDeletePullQueue, task);
  }

  /** Enqueues a task to asynchronously refresh DNS for a renamed host. */
  public void enqueueAsyncDnsRefresh(HostResource host, DateTime now) {
    VKey<HostResource> hostKey = host.createVKey();
    logger.atInfo().log("Enqueuing async DNS refresh for renamed host %s.", hostKey);
    addTaskToQueueWithRetry(
        asyncDnsRefreshPullQueue,
        TaskOptions.Builder.withMethod(Method.PULL)
            .param(PARAM_HOST_KEY, hostKey.getOfyKey().getString())
            .param(PARAM_REQUESTED_TIME, now.toString()));
  }

  /**
   * Enqueues a task to asynchronously re-lock a registry-locked domain after it was unlocked.
   *
   * <p>Note: the relockDuration must be present on the lock object.
   */
  public void enqueueDomainRelock(RegistryLock lock) {
    checkArgument(
        lock.getRelockDuration().isPresent(),
        "Lock with ID %s not configured for relock",
        lock.getRevisionId());
    addTaskToQueueWithRetry(
        asyncActionsPushQueue,
        TaskOptions.Builder.withUrl(RelockDomainAction.PATH)
            .method(Method.POST)
            .param(
                RelockDomainAction.OLD_UNLOCK_REVISION_ID_PARAM,
                String.valueOf(lock.getRevisionId()))
            .countdownMillis(lock.getRelockDuration().get().getMillis()));
  }

  /**
   * Adds a task to a queue with retrying, to avoid aborting the entire flow over a transient issue
   * enqueuing a task.
   */
  private void addTaskToQueueWithRetry(final Queue queue, final TaskOptions task) {
    retrier.callWithRetry(() -> queue.add(task), TransientFailureException.class);
  }
}
