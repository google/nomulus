// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.dns.DnsModule.PARAM_DNS_JITTER_SECONDS;
import static google.registry.dns.DnsModule.PARAM_DNS_WRITER;
import static google.registry.dns.DnsModule.PARAM_DOMAINS;
import static google.registry.dns.DnsModule.PARAM_HOSTS;
import static google.registry.dns.DnsModule.PARAM_LOCK_INDEX;
import static google.registry.dns.DnsModule.PARAM_NUM_PUBLISH_LOCKS;
import static google.registry.dns.DnsModule.PARAM_PUBLISH_TASK_ENQUEUED;
import static google.registry.dns.DnsModule.PARAM_REFRESH_REQUEST_TIME;
import static google.registry.dns.DnsUtils.DNS_PUBLISH_PUSH_QUEUE_NAME;
import static google.registry.dns.DnsUtils.deleteRequests;
import static google.registry.dns.DnsUtils.readAndUpdateRequestsWithLatestProcessTime;
import static google.registry.request.Action.Method.POST;
import static google.registry.request.RequestParameters.PARAM_TLD;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DomainNameUtils.getSecondLevelDomain;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.cloud.tasks.v2.Task;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.flogger.FluentLogger;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import google.registry.batch.CloudTasksUtils;
import google.registry.config.RegistryConfig.Config;
import google.registry.dns.DnsUtils.TargetType;
import google.registry.model.common.DnsRefreshRequest;
import google.registry.model.tld.Tld;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Action for fanning out DNS refresh tasks by TLD, using data taken from {@link DnsRefreshRequest}
 * table.
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/readDnsRefreshRequests",
    automaticallyPrintOk = true,
    method = POST,
    auth = Auth.AUTH_ADMIN)
public final class ReadDnsRefreshRequestsAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final int tldUpdateBatchSize;
  private final Duration requestedMaximumDuration;
  private final Optional<Integer> jitterSeconds;
  private final String tld;
  private final Clock clock;
  private final HashFunction hashFunction;
  private final CloudTasksUtils cloudTasksUtils;

  @Inject
  ReadDnsRefreshRequestsAction(
      @Config("dnsTldUpdateBatchSize") int tldUpdateBatchSize,
      @Config("readDnsRefreshRequestsActionRuntime") Duration requestedMaximumDuration,
      @Parameter(PARAM_DNS_JITTER_SECONDS) Optional<Integer> jitterSeconds,
      @Parameter(PARAM_TLD) String tld,
      Clock clock,
      HashFunction hashFunction,
      CloudTasksUtils cloudTasksUtils) {
    this.tldUpdateBatchSize = tldUpdateBatchSize;
    this.requestedMaximumDuration = requestedMaximumDuration;
    this.jitterSeconds = jitterSeconds;
    this.tld = tld;
    this.clock = clock;
    this.hashFunction = hashFunction;
    this.cloudTasksUtils = cloudTasksUtils;
  }

  /**
   * Reads requests up to the maximum requested runtime, and enqueues update batches from the these
   * requests.
   */
  @Override
  public void run() {
    if (Tld.get(tld).getDnsPaused()) {
      logger.atInfo().log("The queue updated is paused for TLD: %s.", tld);
      return;
    }
    DateTime requestedEndTime = clock.nowUtc().plus(requestedMaximumDuration);
    // See getLockIndex(), requests are evenly distributed to [1, numDnsPublishLocks], so each
    // bucket would be roughly the size of tldUpdateBatchSize.
    int processBatchSize = tldUpdateBatchSize * Tld.get(tld).getNumDnsPublishLocks();
    while (requestedEndTime.isAfter(clock.nowUtc())) {
      ImmutableList<DnsRefreshRequest> requests =
          readAndUpdateRequestsWithLatestProcessTime(
              tld, requestedMaximumDuration, processBatchSize);
      logger.atInfo().log("Read %d DNS update requests for TLD %s.", requests.size(), tld);
      if (!requests.isEmpty()) {
        processRequests(requests);
      }
      if (requests.size() < processBatchSize) {
        return;
      }
    }
  }
  /**
   * Subdivides {@link DnsRefreshRequest} into buckets by lock index, enqueue a Cloud Tasks task per
   * bucket, and then delete the requests in each bucket.
   */
  void processRequests(Collection<DnsRefreshRequest> requests) {
    int numPublishLocks = Tld.get(tld).getNumDnsPublishLocks();
    requests.stream()
        .collect(
            toImmutableSetMultimap(
                request -> getLockIndex(numPublishLocks, request), request -> request))
        .asMap()
        .forEach(
            (lockIndex, bucketedRequests) -> {
              try {
                enqueueUpdates(lockIndex, numPublishLocks, bucketedRequests);
                deleteRequests(bucketedRequests);
                logger.atInfo().log(
                    "Processed %d DNS update requests for TLD %s.", bucketedRequests.size(), tld);
              } catch (Exception e) {
                // Log but continue to process the next bucket. The failed tasks will NOT be
                // deleted and will be retried after the cooldown period has passed.
                logger.atSevere().withCause(e).log(
                    "Error processing DNS update requests: %s", bucketedRequests);
              }
            });
  }

  /**
   * Returns the lock index for a given {@link DnsRefreshRequest}.
   *
   * <p>We hash the second level domain for all records, to group in-bailiwick hosts (the only ones
   * we refresh DNS for) with their superordinate domains. We use consistent hashing to determine
   * the lock index because it gives us [0,N) bucketing properties out of the box, then add 1 to
   * make indexes within [1,N].
   */
  int getLockIndex(int numPublishLocks, DnsRefreshRequest request) {
    String domain = getSecondLevelDomain(request.getName(), tld);
    return Hashing.consistentHash(hashFunction.hashString(domain, UTF_8), numPublishLocks) + 1;
  }

  /** Creates DNS refresh tasks for all writers for the tld within a lock index. */
  void enqueueUpdates(int lockIndex, int numPublishLocks, Collection<DnsRefreshRequest> requests) {
    ImmutableList.Builder<String> domainsBuilder = new ImmutableList.Builder<>();
    ImmutableList.Builder<String> hostsBuilder = new ImmutableList.Builder<>();
    DateTime earliestRequestTime = END_OF_TIME;
    for (DnsRefreshRequest request : requests) {
      if (request.getRequestTime().isBefore(earliestRequestTime)) {
        earliestRequestTime = request.getRequestTime();
      }
      String name = request.getName();
      if (request.getType().equals(TargetType.DOMAIN)) {
        domainsBuilder.add(name);
      } else {
        hostsBuilder.add(name);
      }
    }
    ImmutableList<String> domains = domainsBuilder.build();
    ImmutableList<String> hosts = hostsBuilder.build();
    for (String dnsWriter : Tld.get(tld).getDnsWriters()) {
      Task task =
          cloudTasksUtils.createTaskWithJitter(
              PublishDnsUpdatesAction.class,
              POST,
              ImmutableMultimap.<String, String>builder()
                  .put(PARAM_TLD, tld)
                  .put(PARAM_DNS_WRITER, dnsWriter)
                  .put(PARAM_LOCK_INDEX, Integer.toString(lockIndex))
                  .put(PARAM_NUM_PUBLISH_LOCKS, Integer.toString(numPublishLocks))
                  .put(PARAM_PUBLISH_TASK_ENQUEUED, clock.nowUtc().toString())
                  .put(PARAM_REFRESH_REQUEST_TIME, earliestRequestTime.toString())
                  .put(PARAM_DOMAINS, Joiner.on(',').join(domains))
                  .put(PARAM_HOSTS, Joiner.on(',').join(hosts))
                  .build(),
              jitterSeconds);
      cloudTasksUtils.enqueue(DNS_PUBLISH_PUSH_QUEUE_NAME, task);
      logger.atInfo().log(
          "Enqueued DNS update request for (TLD %s, lock %d) with %d domains and %d hosts.",
          tld, lockIndex, domains.size(), hosts.size());
    }
  }
}
