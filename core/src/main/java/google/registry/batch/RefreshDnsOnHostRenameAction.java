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

import static com.google.appengine.api.taskqueue.QueueConstants.maxLeaseCount;
import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_HOST_KEY;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_REQUESTED_TIME;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_HOST_RENAME;
import static google.registry.batch.AsyncTaskMetrics.OperationType.DNS_REFRESH;
import static google.registry.mapreduce.inputs.EppResourceInputs.createEntityInput;
import static google.registry.model.EppResourceUtils.isActive;
import static google.registry.model.EppResourceUtils.isDeleted;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.DateTimeUtils.latestOf;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static org.joda.time.Duration.standardHours;

import com.google.appengine.api.taskqueue.LeaseOptions;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.TaskHandle;
import com.google.appengine.api.taskqueue.TransientFailureException;
import com.google.appengine.tools.mapreduce.Mapper;
import com.google.appengine.tools.mapreduce.Reducer;
import com.google.appengine.tools.mapreduce.ReducerInput;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.googlecode.objectify.Key;
import google.registry.batch.AsyncTaskMetrics.OperationResult;
import google.registry.dns.DnsQueue;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.mapreduce.inputs.NullInput;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.server.Lock;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.util.NonFinalForTesting;
import google.registry.util.RequestStatusChecker;
import google.registry.util.Retrier;
import google.registry.util.SystemClock;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** Performs batched DNS refreshes for applicable domains following a host rename. */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/refreshDnsOnHostRename",
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class RefreshDnsOnHostRenameAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Duration LEASE_LENGTH = standardHours(4);

  @Inject AsyncTaskMetrics asyncTaskMetrics;
  @Inject Clock clock;
  @Inject MapreduceRunner mrRunner;
  @Inject @Named(QUEUE_ASYNC_HOST_RENAME) Queue pullQueue;
  @Inject RequestStatusChecker requestStatusChecker;
  @Inject Response response;
  @Inject Retrier retrier;
  @Inject RefreshDnsOnHostRenameAction() {}

  @Override
  public void run() {
    // Check if the lock can be acquired, and if not, a previous run of this mapreduce is still
    // executing, so return early.
    Optional<Lock> lock =
        Lock.acquire(
            RefreshDnsOnHostRenameAction.class.getSimpleName(),
            null,
            LEASE_LENGTH,
            requestStatusChecker,
            false);

    if (!lock.isPresent()) {
      logRespondAndUnlock(INFO, "Can't acquire lock; aborting.", lock);
      return;
    }

    // Lease the async tasks to process.
    LeaseOptions options =
        LeaseOptions.Builder.withCountLimit(maxLeaseCount())
            .leasePeriod(LEASE_LENGTH.getStandardSeconds(), SECONDS);
    List<TaskHandle> tasks = pullQueue.leaseTasks(options);
    asyncTaskMetrics.recordDnsRefreshBatchSize(tasks.size());

    // Check if there are no tasks to process, and if so, return early.
    if (tasks.isEmpty()) {
      logRespondAndUnlock(
          INFO, "No DNS refresh on host rename tasks to process in pull queue; finishing.", lock);
      return;
    }

    ImmutableList.Builder<DnsRefreshRequest> requestsBuilder = new ImmutableList.Builder<>();
    ImmutableList.Builder<Key<HostResource>> hostKeys = new ImmutableList.Builder<>();
    final List<DnsRefreshRequest> requestsToDelete = new ArrayList<>();

    for (TaskHandle task : tasks) {
      try {
        DnsRefreshRequest request = DnsRefreshRequest.createFromTask(task, clock.nowUtc());
        if (request.isRefreshNeeded()) {
          requestsBuilder.add(request);
          hostKeys.add(request.hostKey());
        } else {
          // Skip hosts that are deleted.
          requestsToDelete.add(request);
        }
      } catch (Exception e) {
        logger.atSevere().withCause(e).log(
            "Could not parse DNS refresh for host request, delaying task for a day: %s", task);
        // Grab the lease for a whole day, so it won't continue throwing errors every five minutes.
        pullQueue.modifyTaskLease(task, 1L, DAYS);
      }
    }

    deleteTasksWithRetry(
        requestsToDelete, pullQueue, asyncTaskMetrics, retrier, OperationResult.STALE);
    ImmutableList<DnsRefreshRequest> refreshRequests = requestsBuilder.build();
    if (refreshRequests.isEmpty()) {
      logRespondAndUnlock(
          INFO, "No async DNS refreshes to process because all renamed hosts are deleted.", lock);
    } else {
      logger.atInfo().log(
          "Processing asynchronous DNS refresh for renamed hosts: %s", hostKeys.build());
      runMapreduce(refreshRequests, lock);
    }
  }

  private void runMapreduce(ImmutableList<DnsRefreshRequest> refreshRequests, Optional<Lock> lock) {
    try {
      mrRunner
          .setJobName("Enqueue DNS refreshes for domains referencing renamed hosts")
          .setModuleName("backend")
          .setDefaultReduceShards(1)
          .runMapreduce(
              new RefreshDnsOnHostRenameMapper(refreshRequests, retrier),
              new RefreshDnsOnHostRenameReducer(refreshRequests, lock.get(), retrier),
              // Add an extra NullInput so that the reducer always fires exactly once.
              ImmutableList.of(new NullInput<>(), createEntityInput(DomainBase.class)))
          .sendLinkToMapreduceConsole(response);
    } catch (Throwable t) {
      logRespondAndUnlock(
          SEVERE, "Error starting mapreduce to refresh DNS for renamed hosts.", lock);
    }
  }

  private void logRespondAndUnlock(Level level, String message, Optional<Lock> lock) {
    logger.at(level).log(message);
    response.setPayload(message);
    lock.ifPresent(Lock::release);
  }

  /** Map over domains and refresh the DNS of those that reference the renamed hosts. */
  public static class RefreshDnsOnHostRenameMapper
      extends Mapper<DomainBase, Boolean, Boolean> {

    private static final long serialVersionUID = -5261698524424335531L;
    private static final DnsQueue dnsQueue = DnsQueue.create();

    private final ImmutableList<DnsRefreshRequest> refreshRequests;
    private final Retrier retrier;

    RefreshDnsOnHostRenameMapper(
        ImmutableList<DnsRefreshRequest> refreshRequests, Retrier retrier) {
      this.refreshRequests = refreshRequests;
      this.retrier = retrier;
    }

    @Override
    public final void map(@Nullable final DomainBase domain) {
      if (domain == null) {
        // Emit a single value so that the reducer always runs.  The key and value don't matter.
        emit(true, true);
        return;
      }
      Key<HostResource> referencingHostKey = null;
      for (DnsRefreshRequest request : refreshRequests) {
        if (isActive(domain, request.lastUpdateTime())
            && domain.getNameservers().contains(VKey.from(request.hostKey()))) {
          referencingHostKey = request.hostKey();
          break;
        }
      }
      if (referencingHostKey != null) {
        retrier.callWithRetry(
            () -> dnsQueue.addDomainRefreshTask(domain.getDomainName()),
            TransientFailureException.class);
        logger.atInfo().log(
            "Enqueued DNS refresh for domain %s referenced by host %s.",
            domain.getDomainName(), referencingHostKey);
        getContext().incrementCounter("domains refreshed");
      } else {
        getContext().incrementCounter("domains not refreshed");
      }

      // Don't catch errors -- we allow the mapreduce to terminate on any errors that can't be
      // resolved by retrying the transaction. The reducer only fires if the mapper completes
      // without errors, meaning that it is acceptable to delete all tasks.
    }
  }

  /**
   * A reducer that always fires exactly once.
   *
   * <p>This is really a reducer in name only; what it's really doing is waiting for all of the
   * mapper tasks to finish, and then delete the pull queue tasks. Note that this only happens if
   * the mapper completes execution without errors.
   */
  public static class RefreshDnsOnHostRenameReducer extends Reducer<Boolean, Boolean, Void> {

    private static final long serialVersionUID = 9077366205249562118L;

    @NonFinalForTesting
    private static AsyncTaskMetrics asyncTaskMetrics = new AsyncTaskMetrics(new SystemClock());

    private final Lock lock;
    private final Retrier retrier;
    private final List<DnsRefreshRequest> refreshRequests;

    RefreshDnsOnHostRenameReducer(
        List<DnsRefreshRequest> refreshRequests, Lock lock, Retrier retrier) {
      this.refreshRequests = refreshRequests;
      this.lock = lock;
      this.retrier = retrier;
    }

    @Override
    public void reduce(Boolean key, ReducerInput<Boolean> values) {
      // The reduce() method is run precisely once, because the NullInput caused the mapper to emit
      // a dummy value once.
      deleteTasksWithRetry(
          refreshRequests,
          getQueue(QUEUE_ASYNC_HOST_RENAME),
          asyncTaskMetrics,
          retrier,
          OperationResult.SUCCESS);

      lock.release();
    }
  }

  /** Deletes a list of tasks from the given queue using a retrier. */
  private static void deleteTasksWithRetry(
      final List<DnsRefreshRequest> refreshRequests,
      final Queue queue,
      AsyncTaskMetrics asyncTaskMetrics,
      Retrier retrier,
      OperationResult result) {
    if (refreshRequests.isEmpty()) {
      return;
    }
    final List<TaskHandle> tasks =
        refreshRequests.stream().map(DnsRefreshRequest::task).collect(toImmutableList());
    retrier.callWithRetry(() -> queue.deleteTask(tasks), TransientFailureException.class);
    refreshRequests.forEach(
        r -> asyncTaskMetrics.recordAsyncFlowResult(DNS_REFRESH, result, r.requestedTime()));
  }

  /** A class that encapsulates the values of a request to refresh DNS for a renamed host. */
  @AutoValue
  abstract static class DnsRefreshRequest implements Serializable {

    private static final long serialVersionUID = 1772812852271288622L;

    abstract Key<HostResource> hostKey();
    abstract DateTime lastUpdateTime();
    abstract DateTime requestedTime();
    abstract boolean isRefreshNeeded();
    abstract TaskHandle task();

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setHostKey(Key<HostResource> hostKey);
      abstract Builder setLastUpdateTime(DateTime lastUpdateTime);
      abstract Builder setRequestedTime(DateTime requestedTime);
      abstract Builder setIsRefreshNeeded(boolean isRefreshNeeded);
      abstract Builder setTask(TaskHandle task);
      abstract DnsRefreshRequest build();
    }

    /**
     * Returns a packaged-up {@link DnsRefreshRequest} parsed from a task queue task.
     */
    static DnsRefreshRequest createFromTask(TaskHandle task, DateTime now) throws Exception {
      ImmutableMap<String, String> params = ImmutableMap.copyOf(task.extractParams());
      Key<HostResource> hostKey =
          Key.create(checkNotNull(params.get(PARAM_HOST_KEY), "Host to refresh not specified"));
      HostResource host =
          checkNotNull(ofy().load().key(hostKey).now(), "Host to refresh doesn't exist");
      boolean isHostDeleted =
          isDeleted(host, latestOf(now, host.getUpdateTimestamp().getTimestamp()));
      if (isHostDeleted) {
        logger.atInfo().log("Host %s is already deleted, not refreshing DNS.", hostKey);
      }
      return new AutoValue_RefreshDnsOnHostRenameAction_DnsRefreshRequest.Builder()
          .setHostKey(hostKey)
          .setLastUpdateTime(host.getUpdateTimestamp().getTimestamp())
          .setRequestedTime(
              DateTime.parse(
                  checkNotNull(params.get(PARAM_REQUESTED_TIME), "Requested time not specified")))
          .setIsRefreshNeeded(!isHostDeleted)
          .setTask(task)
          .build();
    }
  }
}
