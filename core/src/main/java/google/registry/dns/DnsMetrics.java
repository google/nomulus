// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.util.RegistryEnvironment.PRODUCTION;

import com.google.common.collect.ImmutableSet;
import com.google.monitoring.metrics.DistributionFitter;
import com.google.monitoring.metrics.EventMetric;
import com.google.monitoring.metrics.ExponentialFitter;
import com.google.monitoring.metrics.FibonacciFitter;
import com.google.monitoring.metrics.IncrementableMetric;
import com.google.monitoring.metrics.LabelDescriptor;
import com.google.monitoring.metrics.MetricRegistryImpl;
import google.registry.util.RegistryEnvironment;
import javax.inject.Inject;
import org.joda.time.Duration;

/** DNS instrumentation. */
// TODO(b/67947699):Once load testing is done, revisit these to rename them and delete the ones that
// we don't expect to need long-term.
public class DnsMetrics {

  /** Disposition of a publish request. */
  public enum PublishStatus { ACCEPTED, REJECTED }

  /** Disposition of writer.commit(). */
  public enum CommitStatus { SUCCESS, FAILURE }

  /** Disposition of the publish action. */
  public enum ActionStatus {
    SUCCESS,
    COMMIT_FAILURE,
    LOCK_FAILURE,
    BAD_WRITER,
    BAD_LOCK_INDEX,
    MAX_RETRIES_EXCEEDED
  }

  private static final ImmutableSet<LabelDescriptor> LABEL_DESCRIPTORS_FOR_PUBLISH_REQUESTS =
      ImmutableSet.of(
          LabelDescriptor.create("tld", "TLD"),
          LabelDescriptor.create(
              "status", "Whether the publish request was accepted or rejected."));

  private static final ImmutableSet<LabelDescriptor> LABEL_DESCRIPTORS_FOR_COMMIT =
      ImmutableSet.of(
          LabelDescriptor.create("tld", "TLD"),
          LabelDescriptor.create("status", "Whether writer.commit() succeeded or failed."),
          LabelDescriptor.create("dnsWriter", "The DnsWriter used."));

  private static final ImmutableSet<LabelDescriptor> LABEL_DESCRIPTORS_FOR_LATENCY =
      ImmutableSet.of(
          LabelDescriptor.create("tld", "TLD"),
          LabelDescriptor.create("status", "Whether the publish succeeded, or why it failed."),
          LabelDescriptor.create("dnsWriter", "The DnsWriter used."));

  // Finer-grained fitter than the DEFAULT_FITTER, allows values between 100 ms and just over 29
  // hours.
  private static final DistributionFitter EXPONENTIAL_FITTER =
      ExponentialFitter.create(20, 2.0, 100.0);

  // Fibonacci fitter more suitible for integer-type values. Allows values between 0 and 10946,
  // which is the 21th Fibonacci number.
  private static final DistributionFitter FIBONACCI_FITTER =
      FibonacciFitter.create(10946);

  private static final IncrementableMetric publishDomainRequests =
      MetricRegistryImpl.getDefault()
          .newIncrementableMetric(
              "/dns/publish_domain_requests",
              "count of publishDomain requests",
              "count",
              LABEL_DESCRIPTORS_FOR_PUBLISH_REQUESTS);

  private static final IncrementableMetric publishHostRequests =
      MetricRegistryImpl.getDefault()
          .newIncrementableMetric(
              "/dns/publish_host_requests",
              "count of publishHost requests",
              "count",
              LABEL_DESCRIPTORS_FOR_PUBLISH_REQUESTS);

  private static final IncrementableMetric commitCount =
      MetricRegistryImpl.getDefault()
          .newIncrementableMetric(
              "/dns/commit_requests",
              "Count of writer.commit() calls",
              "count",
              LABEL_DESCRIPTORS_FOR_COMMIT);

  private static final IncrementableMetric domainsCommittedCount =
      MetricRegistryImpl.getDefault()
          .newIncrementableMetric(
              "/dns/domains_committed",
              "Count of domains committed",
              "count",
              LABEL_DESCRIPTORS_FOR_COMMIT);

  private static final IncrementableMetric hostsCommittedCount =
      MetricRegistryImpl.getDefault()
          .newIncrementableMetric(
              "/dns/hosts_committed",
              "Count of hosts committed",
              "count",
              LABEL_DESCRIPTORS_FOR_COMMIT);

  private static final EventMetric processingTimePerCommitDist =
      MetricRegistryImpl.getDefault()
          .newEventMetric(
              "/dns/per_batch/processing_time",
              "publishDnsUpdates Processing Time",
              "milliseconds",
              LABEL_DESCRIPTORS_FOR_COMMIT,
              EXPONENTIAL_FITTER);

  private static final EventMetric normalizedProcessingTimePerCommitDist =
      MetricRegistryImpl.getDefault()
          .newEventMetric(
              "/dns/per_batch/processing_time_per_dns_update",
              "publishDnsUpdates Processing Time, divided by the batch size",
              "milliseconds",
              LABEL_DESCRIPTORS_FOR_COMMIT,
              EXPONENTIAL_FITTER);

  private static final EventMetric totalBatchSizePerCommitDist =
      MetricRegistryImpl.getDefault()
          .newEventMetric(
              "/dns/per_batch/batch_size",
              "Number of hosts and domains committed in each publishDnsUpdates",
              "count",
              LABEL_DESCRIPTORS_FOR_COMMIT,
              FIBONACCI_FITTER);

  private static final EventMetric processingTimePerItemDist =
      MetricRegistryImpl.getDefault()
          .newEventMetric(
              "/dns/per_item/processing_time",
              "publishDnsUpdates Processing Time",
              "milliseconds",
              LABEL_DESCRIPTORS_FOR_COMMIT,
              EXPONENTIAL_FITTER);

  private static final EventMetric normalizedProcessingTimePerItemDist =
      MetricRegistryImpl.getDefault()
          .newEventMetric(
              "/dns/per_item/processing_time_per_dns_update",
              "publishDnsUpdates Processing Time, divided by the batch size",
              "milliseconds",
              LABEL_DESCRIPTORS_FOR_COMMIT,
              EXPONENTIAL_FITTER);

  private static final EventMetric totalBatchSizePerItemDist =
      MetricRegistryImpl.getDefault()
          .newEventMetric(
              "/dns/per_item/batch_size",
              "Batch sizes for hosts and domains",
              "count",
              LABEL_DESCRIPTORS_FOR_COMMIT,
              FIBONACCI_FITTER);

  private static final EventMetric updateRequestLatency =
      MetricRegistryImpl.getDefault()
          .newEventMetric(
              "/dns/update_latency",
              "Time elapsed since refresh request was created until it was published",
              "milliseconds",
              LABEL_DESCRIPTORS_FOR_LATENCY,
              EXPONENTIAL_FITTER);

  private static final EventMetric publishQueueDelay =
      MetricRegistryImpl.getDefault()
          .newEventMetric(
              "/dns/publish_queue_delay",
              "Time elapsed since the publishDnsUpdates action was created until it was executed",
              "milliseconds",
              LABEL_DESCRIPTORS_FOR_LATENCY,
              EXPONENTIAL_FITTER);

  @Inject
  DnsMetrics() {}

  /**
   * Increment a monotonic counter that tracks calls to {@link
   * google.registry.dns.writer.DnsWriter#publishDomain(String)}, per TLD.
   */
  public void incrementPublishDomainRequests(String tld, long numRequests, PublishStatus status) {
    if (numRequests > 0) {
      publishDomainRequests.incrementBy(numRequests, tld, status.name());
    }
  }

  /**
   * Increment a monotonic counter that tracks calls to {@link
   * google.registry.dns.writer.DnsWriter#publishHost(String)}, per TLD.
   */
  public void incrementPublishHostRequests(String tld, long numRequests, PublishStatus status) {
    if (numRequests > 0) {
      publishHostRequests.incrementBy(numRequests, tld, status.name());
    }
  }

  /**
   * Measures information about the entire batched commit, per TLD.
   *
   * <p>The information includes running times (per item and per commit), and batch sizes (per item
   * and per commit)
   *
   * <p>This is to be used for load testing the system, and will not measure anything in prod.
   */
  void recordCommit(
      String tld,
      String dnsWriter,
      CommitStatus status,
      Duration processingDuration,
      int numberOfDomains,
      int numberOfHosts) {
    commitCount.increment(tld, status.name(), dnsWriter);
    domainsCommittedCount.incrementBy(numberOfDomains, tld, status.name(), dnsWriter);
    hostsCommittedCount.incrementBy(numberOfHosts, tld, status.name(), dnsWriter);

    // We don't want to record the following metrics in production, as they are quite expensive
    if (RegistryEnvironment.get().equals(PRODUCTION)) {
      return;
    }

    int batchSize = numberOfDomains + numberOfHosts;

    processingTimePerCommitDist.record(
        processingDuration.getMillis(), tld, status.name(), dnsWriter);
    processingTimePerItemDist.record(
        processingDuration.getMillis(), batchSize, tld, status.name(), dnsWriter);

    if (batchSize > 0) {
      normalizedProcessingTimePerCommitDist.record(
          processingDuration.getMillis() / (double) batchSize, tld, status.name(), dnsWriter);
      normalizedProcessingTimePerItemDist.record(
          processingDuration.getMillis() / (double) batchSize,
          batchSize,
          tld,
          status.name(),
          dnsWriter);
    }

    totalBatchSizePerCommitDist.record(batchSize, tld, status.name(), dnsWriter);

    totalBatchSizePerItemDist.record(batchSize, batchSize, tld, status.name(), dnsWriter);
  }

  void recordActionResult(
      String tld,
      String dnsWriter,
      ActionStatus status,
      int numberOfItems,
      Duration timeSinceUpdateRequest,
      Duration timeSinceActionEnqueued) {
    updateRequestLatency.record(
        timeSinceUpdateRequest.getMillis(), numberOfItems, tld, status.name(), dnsWriter);
    publishQueueDelay.record(timeSinceActionEnqueued.getMillis(), tld, status.name(), dnsWriter);
  }
}
