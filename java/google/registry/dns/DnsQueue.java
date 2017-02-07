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
import static google.registry.dns.DnsConstants.DNS_TARGET_NAME_PARAM;
import static google.registry.dns.DnsConstants.DNS_TARGET_TYPE_PARAM;
import static google.registry.model.registry.Registries.assertTldExists;
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
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.net.InternetDomainName;
import google.registry.dns.DnsConstants.TargetType;
import google.registry.model.registry.Registries;
import google.registry.util.FormattingLogger;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Named;
import org.joda.time.Duration;

/** Methods for manipulating the queue used for DNS write tasks. */
public class DnsQueue {

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

  private final Queue queue;

  @Inject
  public DnsQueue(@Named(DNS_PULL_QUEUE_NAME) Queue queue) {
    this.queue = queue;
  }

  /**
   * Constructs a new instance.
   *
   * <p><b>Note:</b> Prefer <code>@Inject</code>ing DnsQueue instances instead. You should only use
   * this helper method in situations for which injection does not work, e.g. inside mapper or
   * reducer classes in mapreduces that need to be Serializable.
   */
  public static DnsQueue create() {
    return new DnsQueue(getQueue(DNS_PULL_QUEUE_NAME));
  }

  long writeBatchSize = QueueConstants.maxLeaseCount();

  /**
   * Enqueues the given task type with the given target name to the DNS queue.
   */
  private TaskHandle addToQueue(TargetType targetType, String targetName, String tld) {
    return queue.add(TaskOptions.Builder
        .withDefaults()
        .method(Method.PULL)
        .param(DNS_TARGET_TYPE_PARAM, targetType.toString())
        .param(DNS_TARGET_NAME_PARAM, targetName)
        .param(PARAM_TLD, tld));
  }

  /**
   * Adds a task to the queue to refresh the DNS information for the specified subordinate host.
   */
  public TaskHandle addHostRefreshTask(String fullyQualifiedHostName) {
    Optional<InternetDomainName> tld =
        Registries.findTldForName(InternetDomainName.from(fullyQualifiedHostName));
    checkArgument(tld.isPresent(),
        String.format("%s is not a subordinate host to a known tld", fullyQualifiedHostName));
    return addToQueue(TargetType.HOST, fullyQualifiedHostName, tld.get().toString());
  }

  /** Adds a task to the queue to refresh the DNS information for the specified domain. */
  public TaskHandle addDomainRefreshTask(String fullyQualifiedDomainName) {
    return addToQueue(
        TargetType.DOMAIN,
        fullyQualifiedDomainName,
        assertTldExists(getTldFromDomainName(fullyQualifiedDomainName)));
  }

  /** Adds a task to the queue to refresh the DNS information for the specified zone. */
  public TaskHandle addZoneRefreshTask(String fullyQualifiedZoneName) {
    return addToQueue(TargetType.ZONE, fullyQualifiedZoneName, fullyQualifiedZoneName);
  }

  /** Returns handles for a batch of tasks, leased for the specified duration. */
  public List<TaskHandle> leaseTasks(Duration leaseDuration) {
    try {
      return queue.leaseTasks(leaseDuration.getMillis(), MILLISECONDS, writeBatchSize);
    } catch (TransientFailureException | DeadlineExceededException e) {
      logger.severe(e, "Failed leasing tasks too fast");
      return ImmutableList.of();
    }
  }

  /** Reduce the task lease time to zero, making it immediately available to be leased again. */
  public void dropTaskLease(TaskHandle task) {
    try {
      queue.modifyTaskLease(task, 0, TimeUnit.SECONDS);
    } catch (IllegalStateException e) {
      logger.warningfmt(e, "Failed dropping expired lease: %s", task.getName());
    } catch (TransientFailureException | DeadlineExceededException e) {
      logger.severe(e, "Failed dropping task leases too fast");
    }
  }

  /** Delete the task, removing it from the queue permanently. */
  public void deleteTask(TaskHandle task) {
    try {
      queue.deleteTask(task);
    } catch (TransientFailureException | DeadlineExceededException e) {
      logger.severe(e, "Failed deleting tasks too fast");
    }
  }

  /** Delete a list of tasks, removing them from the queue permanently. */
  public void deleteTasks(List<TaskHandle> tasks) {
    try {
      queue.deleteTask(tasks);
    } catch (TransientFailureException | DeadlineExceededException e) {
      logger.severe(e, "Failed deleting tasks too fast");
    }
  }
}
