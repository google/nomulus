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

import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.dns.DnsConstants.DNS_PULL_QUEUE_NAME;
import static google.registry.dns.DnsConstants.DNS_TARGET_CREATE_TIME_PARAM;
import static google.registry.dns.DnsConstants.DNS_TARGET_NAME_PARAM;
import static google.registry.dns.DnsConstants.DNS_TARGET_TYPE_PARAM;
import static google.registry.model.tld.Registries.assertTldExists;
import static google.registry.request.RequestParameters.PARAM_TLD;
import static google.registry.util.DomainNameUtils.getTldFromDomainName;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueConstants;
import com.google.appengine.api.taskqueue.TaskHandle;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TaskOptions.Method;
import com.google.appengine.api.taskqueue.TransientFailureException;
import com.google.apphosting.api.DeadlineExceededException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.InternetDomainName;
import com.google.common.util.concurrent.RateLimiter;
import google.registry.dns.DnsConstants.TargetType;
import google.registry.model.tld.Registries;
import google.registry.util.Clock;
import google.registry.util.NonFinalForTesting;
import google.registry.util.SystemClock;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import javax.inject.Inject;
import javax.inject.Named;
import org.joda.time.Duration;

/**
 * Methods for manipulating the queue used for DNS write tasks.
 *
 * <p>This includes a {@link RateLimiter} to limit the {@link Queue#leaseTasks} call rate to 9 QPS,
 * to stay under the 10 QPS limit for this function.
 *
 * <p>Note that overlapping calls to {@link ReadDnsQueueAction} (the only place where
 * {@link DnsQueue#leaseTasks} is used) will have different rate limiters, so they could exceed the
 * allowed rate. This should be rare though - because {@link DnsQueue#leaseTasks} is only used in
 * {@link ReadDnsQueueAction}, which is run as a cron job with running time shorter than the cron
 * repeat time - meaning there should never be two instances running at once.
 *
 * @see google.registry.config.RegistryConfig.ConfigModule#provideReadDnsQueueRuntime
 */
public class DnsQueue {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Queue queue;

  final Clock clock;

  // Queue.leaseTasks is limited to 10 requests per second as per
  // https://cloud.google.com/appengine/docs/standard/java/javadoc/com/google/appengine/api/taskqueue/Queue.html
  // "If you generate more than 10 LeaseTasks requests per second, only the first 10 requests will
  // return results. The others will return no results."
  private static final RateLimiter rateLimiter = RateLimiter.create(9);

  @Inject
  public DnsQueue(@Named(DNS_PULL_QUEUE_NAME) Queue queue, Clock clock) {
    this.queue = queue;
    this.clock = clock;
  }

  /**
   * Constructs a new instance.
   *
   * <p><b>Note:</b> Prefer <code>@Inject</code>ing DnsQueue instances instead. You should only use
   * this helper method in situations for which injection does not work, e.g. inside mapper or
   * reducer classes in mapreduces that need to be Serializable.
   */
  public static DnsQueue create() {
    return new DnsQueue(getQueue(DNS_PULL_QUEUE_NAME), new SystemClock());
  }

  @VisibleForTesting
  public static DnsQueue createForTesting(Clock clock) {
    return new DnsQueue(getQueue(DNS_PULL_QUEUE_NAME), clock);
  }

  @NonFinalForTesting
  @VisibleForTesting
  long leaseTasksBatchSize = QueueConstants.maxLeaseCount();

  /** Enqueues the given task type with the given target name to the DNS queue. */
  private TaskHandle addToQueue(
      TargetType targetType, String targetName, String tld, Duration countdown) {
    logger.atInfo().log(
        "Adding task type=%s, target=%s, tld=%s to pull queue %s (%d tasks currently on queue).",
        targetType, targetName, tld, DNS_PULL_QUEUE_NAME, queue.fetchStatistics().getNumTasks());
    return queue.add(
        TaskOptions.Builder.withDefaults()
            .method(Method.PULL)
            .countdownMillis(countdown.getMillis())
            .param(DNS_TARGET_TYPE_PARAM, targetType.toString())
            .param(DNS_TARGET_NAME_PARAM, targetName)
            .param(DNS_TARGET_CREATE_TIME_PARAM, clock.nowUtc().toString())
            .param(PARAM_TLD, tld));
  }

  /** Adds a task to the queue to refresh the DNS information for the specified subordinate host. */
  public TaskHandle addHostRefreshTask(String hostName) {
    Optional<InternetDomainName> tld = Registries.findTldForName(InternetDomainName.from(hostName));
    checkArgument(
        tld.isPresent(), String.format("%s is not a subordinate host to a known tld", hostName));
    return addToQueue(TargetType.HOST, hostName, tld.get().toString(), Duration.ZERO);
  }

  /** Enqueues a task to refresh DNS for the specified domain now. */
  public TaskHandle addDomainRefreshTask(String domainName) {
    return addDomainRefreshTask(domainName, Duration.ZERO);
  }

  /** Enqueues a task to refresh DNS for the specified domain at some point in the future. */
  public TaskHandle addDomainRefreshTask(String domainName, Duration countdown) {
    return addToQueue(
        TargetType.DOMAIN,
        domainName,
        assertTldExists(getTldFromDomainName(domainName)),
        countdown);
  }

  /** Adds a task to the queue to refresh the DNS information for the specified zone. */
  public TaskHandle addZoneRefreshTask(String zoneName) {
    return addToQueue(TargetType.ZONE, zoneName, zoneName, Duration.ZERO);
  }

  /**
   * Returns the maximum number of tasks that can be leased with {@link #leaseTasks}.
   *
   * <p>If this many tasks are returned, then there might be more tasks still waiting in the queue.
   *
   * <p>If less than this number of tasks are returned, then there are no more items in the queue.
   */
  public long getLeaseTasksBatchSize() {
    return leaseTasksBatchSize;
  }

  /** Returns handles for a batch of tasks, leased for the specified duration. */
  public List<TaskHandle> leaseTasks(Duration leaseDuration) {
    try {
      rateLimiter.acquire();
      int numTasks = queue.fetchStatistics().getNumTasks();
      logger.at((numTasks >= leaseTasksBatchSize) ? Level.WARNING : Level.INFO).log(
          "There are %d tasks in the DNS queue '%s'.", numTasks, DNS_PULL_QUEUE_NAME);
      return queue.leaseTasks(leaseDuration.getMillis(), MILLISECONDS, leaseTasksBatchSize);
    } catch (TransientFailureException | DeadlineExceededException e) {
      logger.atSevere().withCause(e).log("Failed leasing tasks too fast.");
      return ImmutableList.of();
    }
  }

  /** Delete a list of tasks, removing them from the queue permanently. */
  public void deleteTasks(List<TaskHandle> tasks) {
    try {
      queue.deleteTask(tasks);
    } catch (TransientFailureException | DeadlineExceededException e) {
      logger.atSevere().withCause(e).log("Failed deleting tasks too fast.");
    }
  }
}
