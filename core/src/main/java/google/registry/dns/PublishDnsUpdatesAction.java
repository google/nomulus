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

package google.registry.dns;

import static google.registry.dns.DnsConstants.DNS_PUBLISH_PUSH_QUEUE_NAME;
import static google.registry.dns.DnsModule.PARAM_DNS_WRITER;
import static google.registry.dns.DnsModule.PARAM_DOMAINS;
import static google.registry.dns.DnsModule.PARAM_HOSTS;
import static google.registry.dns.DnsModule.PARAM_LOCK_INDEX;
import static google.registry.dns.DnsModule.PARAM_NUM_PUBLISH_LOCKS;
import static google.registry.dns.DnsModule.PARAM_PUBLISH_TASK_ENQUEUED;
import static google.registry.dns.DnsModule.PARAM_REFRESH_REQUEST_CREATED;
import static google.registry.request.Action.Method.POST;
import static google.registry.request.RequestParameters.PARAM_TLD;
import static google.registry.util.CollectionUtils.nullToEmpty;

import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.InternetDomainName;
import google.registry.config.RegistryConfig.Config;
import google.registry.dns.DnsMetrics.ActionStatus;
import google.registry.dns.DnsMetrics.CommitStatus;
import google.registry.dns.DnsMetrics.PublishStatus;
import google.registry.dns.writer.DnsWriter;
import google.registry.model.tld.Registry;
import google.registry.request.Action;
import google.registry.request.Header;
import google.registry.request.HttpException.ServiceUnavailableException;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.request.lock.LockHandler;
import google.registry.util.Clock;
import google.registry.util.DomainNameUtils;
import google.registry.util.TaskQueueUtils;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** Task that sends domain and host updates to the DNS server. */
@Action(
    service = Action.Service.BACKEND,
    path = PublishDnsUpdatesAction.PATH,
    method = POST,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class PublishDnsUpdatesAction implements Runnable, Callable<Void> {

  public static final String PATH = "/_dr/task/publishDnsUpdates";
  public static final String LOCK_NAME = "DNS updates";
  public static final String RETRY_HEADER = "X-AppEngine-TaskRetryCount";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject DnsQueue dnsQueue;
  @Inject DnsWriterProxy dnsWriterProxy;
  @Inject DnsMetrics dnsMetrics;
  @Inject @Config("publishDnsUpdatesLockDuration") Duration timeout;

  @Inject
  @Header(RETRY_HEADER)
  int retryCount;

  /**
   * The DNS writer to use for this batch.
   *
   * <p>This comes from the fanout in {@link ReadDnsQueueAction} which dispatches each batch to be
   * published by each DNS writer on the TLD. So this field contains the value of one of the DNS
   * writers configured in {@link Registry#getDnsWriters()}, as of the time the batch was written
   * out (and not necessarily currently).
   */
  @Inject @Parameter(PARAM_DNS_WRITER) String dnsWriter;

  @Inject
  @Parameter(PARAM_PUBLISH_TASK_ENQUEUED)
  DateTime enqueuedTime;

  @Inject
  @Parameter(PARAM_REFRESH_REQUEST_CREATED)
  DateTime itemsCreateTime;

  @Inject @Parameter(PARAM_LOCK_INDEX) int lockIndex;
  @Inject @Parameter(PARAM_NUM_PUBLISH_LOCKS) int numPublishLocks;
  @Inject @Parameter(PARAM_DOMAINS) Set<String> domains;
  @Inject @Parameter(PARAM_HOSTS) Set<String> hosts;
  @Inject @Parameter(PARAM_TLD) String tld;

  @Inject
  @Named(DNS_PUBLISH_PUSH_QUEUE_NAME)
  Queue dnsPublishPushQueue;

  @Inject LockHandler lockHandler;
  @Inject Clock clock;
  @Inject TaskQueueUtils taskQueueUtils;

  @Inject PublishDnsUpdatesAction() {}

  private void recordActionResult(ActionStatus status) {
    DateTime now = clock.nowUtc();

    dnsMetrics.recordActionResult(
        tld,
        dnsWriter,
        status,
        nullToEmpty(domains).size() + nullToEmpty(hosts).size(),
        new Duration(itemsCreateTime, now),
        new Duration(enqueuedTime, now));
    logger.atInfo().log(
        "publishDnsWriter latency statistics: TLD: %s, dnsWriter: %s, actionStatus: %s, "
            + "numItems: %d, timeSinceCreation: %s, timeInQueue: %s.",
        tld,
        dnsWriter,
        status,
        nullToEmpty(domains).size() + nullToEmpty(hosts).size(),
        new Duration(itemsCreateTime, now),
        new Duration(enqueuedTime, now));
  }

  /** Runs the task. */
  @Override
  public void run() {
    try {
    if (!validLockParams()) {
      recordActionResult(ActionStatus.BAD_LOCK_INDEX);
      requeueBatch();
      return;
    }
    // If executeWithLocks fails to get the lock, it does not throw an exception, simply returns
    // false. We need to make sure to take note of this error; otherwise, a failed lock might result
    // in the update task being dequeued and dropped. A message will already have been logged
    // to indicate the problem.
    if (!lockHandler.executeWithLocks(
        this,
        tld,
        timeout,
        String.format("%s-lock %d of %d", LOCK_NAME, lockIndex, numPublishLocks))) {
      recordActionResult(ActionStatus.LOCK_FAILURE);
      throw new ServiceUnavailableException("Lock failure");
    }
    } catch (Throwable e) {
      // Retry the batch 3 times
      if (retryCount < 3) {
        throw e;
      }
      // After 3 retries, split the batch
      if (domains.size() > 1) {
        // split batch and requeue
        splitBatch();
      }
      // If the batch only contains 1 name, allow it 10 retries
      else if (retryCount < 10) {
        throw e;
      }
      // If we get here, we should terminate this task as it is likely a perpetually failing task.
      // TODO(sarahbot): Send an email notifying partner the dns update failed
    }
  }

  /** Runs the task, with the lock. */
  @Override
  public Void call() {
    try {
      processBatch();
    } catch (Throwable e) {
      // Retry the batch 3 times
      if (retryCount < 3) {
        throw e;
      }
      // After 3 retries, split the batch
      if (domains.size() > 1) {
        // split batch and requeue
        splitBatch();
      }
      // If the batch only contains 1 name, allow it 10 retries
      else if (retryCount < 10) {
        throw e;
      }
      // If we get here, we should terminate this task as it is likely a perpetually failing task.
      // TODO(sarahbot): Send an email notifying partner the dns update failed
    }
    return null;
  }

  private void splitBatch() {
    Set<String> set1Domain = new HashSet<>();
    Set<String> set2Domain = new HashSet<>();
    Set<String> set1Host = new HashSet<>();
    Set<String> set2Host = new HashSet<>();

    for (String domain : domains) {
      if (set1Domain.size() < (domains.size() / 2)) {
        set1Domain.add(domain);
      } else {
        set2Domain.add(domain);
      }
    }

    for (String host : hosts) {
      if (set1Host.size() < (hosts.size() / 2)) {
        set1Host.add(host);
      } else {
        set2Host.add(host);
      }
    }

    // Enqueue set 1
    taskQueueUtils.enqueue(
        dnsPublishPushQueue,
        TaskOptions.Builder.withUrl(PATH)
            .param(PARAM_TLD, tld)
            .param(PARAM_DNS_WRITER, dnsWriter)
            .param(PARAM_LOCK_INDEX, Integer.toString(lockIndex))
            .param(PARAM_NUM_PUBLISH_LOCKS, Integer.toString(numPublishLocks))
            .param(PARAM_PUBLISH_TASK_ENQUEUED, clock.nowUtc().toString())
            .param(PARAM_REFRESH_REQUEST_CREATED, itemsCreateTime.toString())
            .param(PARAM_DOMAINS, set1Domain.stream().collect(Collectors.joining(",")))
            .param(PARAM_HOSTS, set1Host.stream().collect(Collectors.joining(","))));

    // Enqueue set 2
    taskQueueUtils.enqueue(
        dnsPublishPushQueue,
        TaskOptions.Builder.withUrl(PATH)
            .param(PARAM_TLD, tld)
            .param(PARAM_DNS_WRITER, dnsWriter)
            .param(PARAM_LOCK_INDEX, Integer.toString(lockIndex))
            .param(PARAM_NUM_PUBLISH_LOCKS, Integer.toString(numPublishLocks))
            .param(PARAM_PUBLISH_TASK_ENQUEUED, clock.nowUtc().toString())
            .param(PARAM_REFRESH_REQUEST_CREATED, itemsCreateTime.toString())
            .param(PARAM_DOMAINS, set2Domain.stream().collect(Collectors.joining(",")))
            .param(PARAM_HOSTS, set2Host.stream().collect(Collectors.joining(","))));
  }

  /** Adds all the domains and hosts in the batch back to the queue to be processed later. */
  private void requeueBatch() {
    logger.atInfo().log("Requeueing batch for retry.");
    for (String domain : nullToEmpty(domains)) {
      dnsQueue.addDomainRefreshTask(domain);
    }
    for (String host : nullToEmpty(hosts)) {
      dnsQueue.addHostRefreshTask(host);
    }
  }

  /** Returns if the lock parameters are valid for this action. */
  private boolean validLockParams() {
    // LockIndex should always be within [1, numPublishLocks]
    if (lockIndex > numPublishLocks || lockIndex <= 0) {
      logger.atSevere().log(
          "Lock index should be within [1,%d], got %d instead.", numPublishLocks, lockIndex);
      return false;
    }
    // Check if the Registry object's num locks has changed since this task was batched
    int registryNumPublishLocks = Registry.get(tld).getNumDnsPublishLocks();
    if (registryNumPublishLocks != numPublishLocks) {
      logger.atWarning().log(
          "Registry numDnsPublishLocks %d out of sync with parameter %d.",
          registryNumPublishLocks, numPublishLocks);
      return false;
    }
    return true;
  }

  /** Steps through the domain and host refreshes contained in the parameters and processes them. */
  private void processBatch() {
    DateTime timeAtStart = clock.nowUtc();

    DnsWriter writer = dnsWriterProxy.getByClassNameForTld(dnsWriter, tld);

    if (writer == null) {
      logger.atWarning().log("Couldn't get writer %s for TLD %s.", dnsWriter, tld);
      recordActionResult(ActionStatus.BAD_WRITER);
      requeueBatch();
      return;
    }

    int domainsPublished = 0;
    int domainsRejected = 0;
    for (String domain : nullToEmpty(domains)) {
      if (!DomainNameUtils.isUnder(
          InternetDomainName.from(domain), InternetDomainName.from(tld))) {
        logger.atSevere().log("%s: skipping domain %s not under TLD.", tld, domain);
        domainsRejected += 1;
      } else {
        writer.publishDomain(domain);
        logger.atInfo().log("%s: published domain %s.", tld, domain);
        domainsPublished += 1;
      }
    }
    dnsMetrics.incrementPublishDomainRequests(tld, domainsPublished, PublishStatus.ACCEPTED);
    dnsMetrics.incrementPublishDomainRequests(tld, domainsRejected, PublishStatus.REJECTED);

    int hostsPublished = 0;
    int hostsRejected = 0;
    for (String host : nullToEmpty(hosts)) {
      if (!DomainNameUtils.isUnder(
          InternetDomainName.from(host), InternetDomainName.from(tld))) {
        logger.atSevere().log("%s: skipping host %s not under TLD.", tld, host);
        hostsRejected += 1;
      } else {
        writer.publishHost(host);
        logger.atInfo().log("%s: published host %s.", tld, host);
        hostsPublished += 1;
      }
    }
    dnsMetrics.incrementPublishHostRequests(tld, hostsPublished, PublishStatus.ACCEPTED);
    dnsMetrics.incrementPublishHostRequests(tld, hostsRejected, PublishStatus.REJECTED);

    // If we got here it means we managed to stage the entire batch without any errors.
    // Next we will commit the batch.
    CommitStatus commitStatus = CommitStatus.FAILURE;
    ActionStatus actionStatus = ActionStatus.COMMIT_FAILURE;
    try {
      writer.commit();
      // No error was thrown
      commitStatus = CommitStatus.SUCCESS;
      actionStatus = ActionStatus.SUCCESS;
    } finally {
      recordActionResult(actionStatus);
      Duration duration = new Duration(timeAtStart, clock.nowUtc());
      dnsMetrics.recordCommit(
          tld, dnsWriter, commitStatus, duration, domainsPublished, hostsPublished);
      logger.atInfo().log(
          "writer.commit() statistics: TLD: %s, dnsWriter: %s, commitStatus: %s, duration: %s, "
              + "domainsPublished: %d, domainsRejected: %d, hostsPublished: %d, hostsRejected: %d.",
          tld,
          dnsWriter,
          commitStatus,
          duration,
          domainsPublished,
          domainsRejected,
          hostsPublished,
          hostsRejected);
    }
  }
}
