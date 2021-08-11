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

import static com.google.common.collect.ImmutableSetMultimap.toImmutableSetMultimap;
import static com.google.common.collect.Sets.difference;
import static google.registry.dns.DnsConstants.DNS_PUBLISH_PUSH_QUEUE_NAME;
import static google.registry.dns.DnsConstants.DNS_TARGET_CREATE_TIME_PARAM;
import static google.registry.dns.DnsConstants.DNS_TARGET_NAME_PARAM;
import static google.registry.dns.DnsConstants.DNS_TARGET_TYPE_PARAM;
import static google.registry.dns.DnsModule.PARAM_DNS_WRITER;
import static google.registry.dns.DnsModule.PARAM_DOMAINS;
import static google.registry.dns.DnsModule.PARAM_HOSTS;
import static google.registry.dns.DnsModule.PARAM_LOCK_INDEX;
import static google.registry.dns.DnsModule.PARAM_NUM_PUBLISH_LOCKS;
import static google.registry.dns.DnsModule.PARAM_PUBLISH_TASK_ENQUEUED;
import static google.registry.dns.DnsModule.PARAM_REFRESH_REQUEST_CREATED;
import static google.registry.request.RequestParameters.PARAM_TLD;
import static google.registry.util.DomainNameUtils.getSecondLevelDomain;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.TaskHandle;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.flogger.FluentLogger;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import google.registry.config.RegistryConfig.Config;
import google.registry.dns.DnsConstants.TargetType;
import google.registry.model.tld.Registries;
import google.registry.model.tld.Registry;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.util.TaskQueueUtils;
import java.io.UnsupportedEncodingException;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Action for fanning out DNS refresh tasks by TLD, using data taken from the DNS pull queue.
 *
 * <h3>Parameters Reference</h3>
 *
 * <ul>
 *   <li>{@code jitterSeconds} Randomly delay each task by up to this many seconds.
 * </ul>
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/cron/readDnsQueue",
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class ReadDnsQueueAction implements Runnable {

  private static final String PARAM_JITTER_SECONDS = "jitterSeconds";
  private static final Random random = new Random();
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * Buffer time since the end of this action until retriable tasks are available again.
   *
   * <p>We read batches of tasks from the queue in a loop. Any task that will need to be retried has
   * to be kept out of the queue for the duration of this action - otherwise we will lease it again
   * in a subsequent loop.
   *
   * <p>The 'requestedMaximumDuration' value is the maximum delay between the first and last calls
   * to lease tasks, hence we want the lease duration to be (slightly) longer than that.
   * LEASE_PADDING is the value we add to {@link #requestedMaximumDuration} to make sure the lease
   * duration is indeed longer.
   */
  private static final Duration LEASE_PADDING = Duration.standardMinutes(1);

  @Inject @Config("dnsTldUpdateBatchSize") int tldUpdateBatchSize;
  @Inject @Config("readDnsQueueActionRuntime") Duration requestedMaximumDuration;
  @Inject @Named(DNS_PUBLISH_PUSH_QUEUE_NAME) Queue dnsPublishPushQueue;
  @Inject @Parameter(PARAM_JITTER_SECONDS) Optional<Integer> jitterSeconds;
  @Inject Clock clock;
  @Inject DnsQueue dnsQueue;
  @Inject HashFunction hashFunction;
  @Inject TaskQueueUtils taskQueueUtils;
  @Inject ReadDnsQueueAction() {}

  /** Container for items we pull out of the DNS pull queue and process for fanout. */
  @AutoValue
  abstract static class RefreshItem implements Comparable<RefreshItem> {
    static RefreshItem create(TargetType type, String name, DateTime creationTime) {
      return new AutoValue_ReadDnsQueueAction_RefreshItem(type, name, creationTime);
    }

    abstract TargetType type();

    abstract String name();

    abstract DateTime creationTime();

    @Override
    public int compareTo(RefreshItem other) {
      return ComparisonChain.start()
          .compare(this.type(), other.type())
          .compare(this.name(), other.name())
          .compare(this.creationTime(), other.creationTime())
          .result();
    }
  }

  /** Leases all tasks from the pull queue and creates per-tld update actions for them. */
  @Override
  public void run() {
    DateTime requestedEndTime = clock.nowUtc().plus(requestedMaximumDuration);
    ImmutableSet<String> tlds = Registries.getTlds();
    while (requestedEndTime.isAfterNow()) {
      List<TaskHandle> tasks = dnsQueue.leaseTasks(requestedMaximumDuration.plus(LEASE_PADDING));
      logger.atInfo().log("Leased %d DNS update tasks.", tasks.size());
      if (!tasks.isEmpty()) {
        dispatchTasks(ImmutableSet.copyOf(tasks), tlds);
      }
      if (tasks.size() < dnsQueue.getLeaseTasksBatchSize()) {
        return;
      }
    }
  }

  /** A set of tasks grouped based on the action to take on them. */
  @AutoValue
  abstract static class ClassifiedTasks {

    /**
     * List of tasks we want to keep in the queue (want to retry in the future).
     *
     * <p>Normally, any task we lease from the queue will be deleted - either because we are going
     * to process it now (these tasks are part of refreshItemsByTld), or because these tasks are
     * "corrupt" in some way (don't parse, don't have the required parameters etc.).
     *
     * <p>Some tasks however are valid, but can't be processed at the moment. These tasks will be
     * kept in the queue for future processing.
     *
     * <p>This includes tasks belonging to paused TLDs (which we want to process once the TLD is
     * unpaused) and tasks belonging to (currently) unknown TLDs.
     */
    abstract ImmutableSet<TaskHandle> tasksToKeep();

    /** The paused TLDs for which we found at least one refresh request. */
    abstract ImmutableSet<String> pausedTlds();

    /**
     * The unknown TLDs for which we found at least one refresh request.
     *
     * <p>Unknown TLDs might have valid requests because the list of TLDs is heavily cached. Hence,
     * when we add a new TLD - it is possible that some instances will not have that TLD in their
     * list yet. We don't want to discard these tasks, so we wait a bit and retry them again.
     *
     * <p>This is less likely for production TLDs but is quite likely for test TLDs where we might
     * create a TLD and then use it within seconds.
     */
    abstract ImmutableSet<String> unknownTlds();

    /**
     * All the refresh items we need to actually fulfill, grouped by TLD.
     *
     * <p>By default, the multimap is ordered - this can be changed with the builder.
     *
     * <p>The items for each TLD will be grouped together, and domains and hosts will be grouped
     * within a TLD.
     *
     * <p>The grouping and ordering of domains and hosts is not technically necessary, but a
     * predictable ordering makes it possible to write detailed tests.
     */
    abstract ImmutableSetMultimap<String, RefreshItem> refreshItemsByTld();

    static Builder builder() {
      Builder builder = new AutoValue_ReadDnsQueueAction_ClassifiedTasks.Builder();
      builder
          .refreshItemsByTldBuilder()
          .orderKeysBy(Ordering.natural())
          .orderValuesBy(Ordering.natural());
      return builder;
    }

    @AutoValue.Builder
    abstract static class Builder {
      abstract ImmutableSet.Builder<TaskHandle> tasksToKeepBuilder();
      abstract ImmutableSet.Builder<String> pausedTldsBuilder();
      abstract ImmutableSet.Builder<String> unknownTldsBuilder();
      abstract ImmutableSetMultimap.Builder<String, RefreshItem> refreshItemsByTldBuilder();

      abstract ClassifiedTasks build();
    }
  }

  /**
   * Creates per-tld update actions for tasks leased from the pull queue.
   *
   * <p>Will return "irrelevant" tasks to the queue for future processing. "Irrelevant" tasks are
   * tasks for paused TLDs or tasks for TLDs not part of {@link Registries#getTlds()}.
   */
  private void dispatchTasks(ImmutableSet<TaskHandle> tasks, ImmutableSet<String> tlds) {
    ClassifiedTasks classifiedTasks = classifyTasks(tasks, tlds);
    if (!classifiedTasks.pausedTlds().isEmpty()) {
      logger.atInfo().log(
          "The dns-pull queue is paused for TLDs: %s.", classifiedTasks.pausedTlds());
    }
    if (!classifiedTasks.unknownTlds().isEmpty()) {
      logger.atWarning().log(
          "The dns-pull queue has unknown TLDs: %s.", classifiedTasks.unknownTlds());
    }
    bucketRefreshItems(classifiedTasks.refreshItemsByTld());
    if (!classifiedTasks.tasksToKeep().isEmpty()) {
      logger.atWarning().log(
          "Keeping %d DNS update tasks in the queue.", classifiedTasks.tasksToKeep().size());
    }
    // Delete the tasks we don't want to see again from the queue.
    //
    // tasksToDelete includes both the tasks that we already fulfilled in this call (were part of
    // refreshItemsByTld) and "corrupt" tasks we weren't able to parse correctly (this shouldn't
    // happen, and we logged a "severe" error)
    //
    // We let the lease on the rest of the tasks (the tasks we want to keep) expire on its own. The
    // reason we don't release these tasks back to the queue immediately is that we are going to
    // immediately read another batch of tasks from the queue - and we don't want to get the same
    // tasks again.
    ImmutableSet<TaskHandle> tasksToDelete =
        difference(tasks, classifiedTasks.tasksToKeep()).immutableCopy();
    logger.atInfo().log("Removing %d DNS update tasks from the queue.", tasksToDelete.size());
    dnsQueue.deleteTasks(tasksToDelete.asList());
    logger.atInfo().log("Done processing DNS tasks.");
  }

  /**
   * Classifies the given tasks based on what action we need to take on them.
   *
   * <p>Note that some tasks might appear in multiple categories (if multiple actions are to be
   * taken on them) or in no category (if no action is to be taken on them)
   */
  private static ClassifiedTasks classifyTasks(
      ImmutableSet<TaskHandle> tasks, ImmutableSet<String> tlds) {

    ClassifiedTasks.Builder classifiedTasksBuilder = ClassifiedTasks.builder();

    // Read all tasks on the DNS pull queue and load them into the refresh item multimap.
    for (TaskHandle task : tasks) {
      try {
        Map<String, String> params = ImmutableMap.copyOf(task.extractParams());
        DateTime creationTime = DateTime.parse(params.get(DNS_TARGET_CREATE_TIME_PARAM));
        String tld = params.get(PARAM_TLD);
        if (tld == null) {
          logger.atSevere().log(
              "Discarding invalid DNS refresh request %s; no TLD specified.", task);
        } else if (!tlds.contains(tld)) {
          classifiedTasksBuilder.tasksToKeepBuilder().add(task);
          classifiedTasksBuilder.unknownTldsBuilder().add(tld);
        } else if (Registry.get(tld).getDnsPaused()) {
          classifiedTasksBuilder.tasksToKeepBuilder().add(task);
          classifiedTasksBuilder.pausedTldsBuilder().add(tld);
        } else {
          String typeString = params.get(DNS_TARGET_TYPE_PARAM);
          String name = params.get(DNS_TARGET_NAME_PARAM);
          TargetType type = TargetType.valueOf(typeString);
          switch (type) {
            case DOMAIN:
            case HOST:
              classifiedTasksBuilder
                  .refreshItemsByTldBuilder()
                  .put(tld, RefreshItem.create(type, name, creationTime));
              break;
            default:
              logger.atSevere().log(
                  "Discarding DNS refresh request %s of type %s.", task, typeString);
              break;
          }
        }
      } catch (RuntimeException | UnsupportedEncodingException e) {
        logger.atSevere().withCause(e).log("Discarding invalid DNS refresh request %s.", task);
      }
    }
    return classifiedTasksBuilder.build();
  }

  /**
   * Subdivides the tld to {@link RefreshItem} multimap into buckets by lock index, if applicable.
   *
   * <p>If the tld has numDnsPublishLocks <= 1, we enqueue all updates on the default lock 1 of 1.
   */
  private void bucketRefreshItems(ImmutableSetMultimap<String, RefreshItem> refreshItemsByTld) {
    // Loop through the multimap by TLD and generate refresh tasks for the hosts and domains for
    // each configured DNS writer.
    for (Map.Entry<String, Collection<RefreshItem>> tldRefreshItemsEntry
        : refreshItemsByTld.asMap().entrySet()) {
      String tld = tldRefreshItemsEntry.getKey();
      int numPublishLocks = Registry.get(tld).getNumDnsPublishLocks();
      // 1 lock or less implies no TLD-wide locks, simply enqueue everything under lock 1 of 1
      if (numPublishLocks <= 1) {
        enqueueUpdates(tld, 1, 1, tldRefreshItemsEntry.getValue());
      } else {
        tldRefreshItemsEntry
            .getValue()
            .stream()
            .collect(
                toImmutableSetMultimap(
                    refreshItem -> getLockIndex(tld, numPublishLocks, refreshItem),
                    refreshItem -> refreshItem))
            .asMap()
            .entrySet()
            .forEach(
                entry -> enqueueUpdates(tld, entry.getKey(), numPublishLocks, entry.getValue()));
      }
    }
  }

  /**
   * Returns the lock index for a given refreshItem.
   *
   * <p>We hash the second level domain domain for all records, to group in-balliwick hosts (the
   * only ones we refresh DNS for) with their superordinate domains. We use consistent hashing to
   * determine the lock index because it gives us [0,N) bucketing properties out of the box, then
   * add 1 to make indexes within [1,N].
   */
  private int getLockIndex(String tld, int numPublishLocks, RefreshItem refreshItem) {
    String domain = getSecondLevelDomain(refreshItem.name(), tld);
    return Hashing.consistentHash(hashFunction.hashString(domain, UTF_8), numPublishLocks) + 1;
  }

  /**
   * Creates DNS refresh tasks for all writers for the tld within a lock index and batches large
   * updates into smaller chunks.
   */
  private void enqueueUpdates(
      String tld, int lockIndex, int numPublishLocks, Collection<RefreshItem> items) {
    for (List<RefreshItem> chunk : Iterables.partition(items, tldUpdateBatchSize)) {
      DateTime earliestCreateTime =
          chunk.stream().map(RefreshItem::creationTime).min(Comparator.naturalOrder()).get();
      for (String dnsWriter : Registry.get(tld).getDnsWriters()) {
        taskQueueUtils.enqueue(
            dnsPublishPushQueue,
            TaskOptions.Builder.withUrl(PublishDnsUpdatesAction.PATH)
                .countdownMillis(
                    jitterSeconds
                        .map(seconds -> random.nextInt((int) SECONDS.toMillis(seconds)))
                        .orElse(0))
                .param(PARAM_TLD, tld)
                .param(PARAM_DNS_WRITER, dnsWriter)
                .param(PARAM_LOCK_INDEX, Integer.toString(lockIndex))
                .param(PARAM_NUM_PUBLISH_LOCKS, Integer.toString(numPublishLocks))
                .param(PARAM_PUBLISH_TASK_ENQUEUED, clock.nowUtc().toString())
                .param(PARAM_REFRESH_REQUEST_CREATED, earliestCreateTime.toString())
                .param(
                    PARAM_DOMAINS,
                    chunk
                        .stream()
                        .filter(item -> item.type() == TargetType.DOMAIN)
                        .map(RefreshItem::name)
                        .collect(Collectors.joining(",")))
                .param(
                    PARAM_HOSTS,
                    chunk
                        .stream()
                        .filter(item -> item.type() == TargetType.HOST)
                        .map(RefreshItem::name)
                        .collect(Collectors.joining(","))));
      }
    }
  }
}
