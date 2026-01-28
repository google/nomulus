// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.mosapi;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.monitoring.v3.Monitoring;
import com.google.api.services.monitoring.v3.model.CreateTimeSeriesRequest;
import com.google.api.services.monitoring.v3.model.LabelDescriptor;
import com.google.api.services.monitoring.v3.model.Metric;
import com.google.api.services.monitoring.v3.model.MetricDescriptor;
import com.google.api.services.monitoring.v3.model.MonitoredResource;
import com.google.api.services.monitoring.v3.model.Point;
import com.google.api.services.monitoring.v3.model.TimeInterval;
import com.google.api.services.monitoring.v3.model.TimeSeries;
import com.google.api.services.monitoring.v3.model.TypedValue;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.mosapi.MosApiModels.ServiceStatus;
import google.registry.mosapi.MosApiModels.TldServiceState;
import google.registry.request.lock.LockHandler;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.joda.time.Duration;

/** Metrics Exporter for MoSAPI. */
@Singleton
public class MosApiMetrics {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // Google Cloud Monitoring Limit: Max 200 TimeSeries per request
  private static final int MAX_TIMESERIES_PER_REQUEST = 195;

  private static final int METRICS_ALREADY_EXIST = 409;

  // Magic String Constants
  private static final String METRIC_DOMAIN = "custom.googleapis.com/mosapi/";
  private static final String PROJECT_RESOURCE_PREFIX = "projects/";
  private static final String RESOURCE_TYPE_GLOBAL = "global";
  private static final String LABEL_PROJECT_ID = "project_id";
  private static final String LABEL_TLD = "tld";
  private static final String LABEL_SERVICE_TYPE = "service_type";

  // Lock Constants
  private static final String LOCK_NAME = "MosApiMetricCreation";
  private static final Duration LOCK_LEASE_TIME = Duration.standardSeconds(30);
  // Metric Names
  private static final String METRIC_TLD_STATUS = "tld_status";
  private static final String METRIC_SERVICE_STATUS = "service_status";
  private static final String METRIC_EMERGENCY_USAGE = "emergency_usage";
  private static final String GAUGE_METRIC_KIND = "GAUGE";

  // Metric Display Names & Descriptions
  private static final String DISPLAY_NAME_TLD_STATUS =
      "Health of TLDs. 1 = UP, 0 = DOWN,  2= DISABLED/NOT_MONITORED";
  private static final String DESC_TLD_STATUS = "Overall Health of TLDs reported from ICANN";

  private static final String DISPLAY_NAME_SERVICE_STATUS =
      "Health of Services. 1 = UP, 0 = DOWN,  2= DISABLED/NOT_MONITORED";
  private static final String DESC_SERVICE_STATUS =
      "Overall Health of Services reported from ICANN";

  private static final String DISPLAY_NAME_EMERGENCY_USAGE =
      "Percentage of Emergency Threshold Consumed";
  private static final String DESC_EMERGENCY_USAGE =
      "Downtime threshold that if reached by any of the monitored Services may cause the TLDs"
          + " Services emergency transition to an interim Registry Operator";

  // MoSAPI Status Constants
  private static final String STATUS_UP_INCONCLUSIVE = "UP-INCONCLUSIVE";
  private static final String STATUS_DOWN = "DOWN";
  private static final String STATUS_DISABLED = "DISABLED";

  private final Monitoring monitoringClient;
  private final String projectId;
  private final String projectName;
  private final Clock clock;
  private final MonitoredResource monitoredResource;
  private final LockHandler lockHandler;
  // Flag to ensure we only create descriptors once, lazily
  private final AtomicBoolean isDescriptorInitialized = new AtomicBoolean(false);

  @Inject
  public MosApiMetrics(
      Monitoring monitoringClient, @Config("projectId") String projectId,
      Clock clock,LockHandler lockHandler) {
    this.monitoringClient = monitoringClient;
    this.projectId = projectId;
    this.clock = clock;
    this.projectName = PROJECT_RESOURCE_PREFIX + projectId;
    this.lockHandler = lockHandler;
    this.monitoredResource =
        new MonitoredResource()
            .setType(RESOURCE_TYPE_GLOBAL)
            .setLabels(ImmutableMap.of(LABEL_PROJECT_ID, projectId));
  }

  /** Accepts a list of states and processes them in a single async batch task. */
  public void recordStates(ImmutableList<TldServiceState> states) {
    // If this is the first time we are recording, ensure descriptors exist.
    if (!isDescriptorInitialized.get()) {
      ensureMetricDescriptorsWithLock();
    }

    try {
      pushBatchMetrics(states);
    } catch (Exception e) {
      throw new RuntimeException("Batch metric push failed", e);
    }
  }
  /**
   * Attempts to create metric descriptors using a distributed lock.
   *
   * <p>If the lock is acquired, this instance creates the descriptors and marks itself initialized.
   * If the lock is busy, it implies another instance is handling it, so we skip and proceed.
   */
  private void ensureMetricDescriptorsWithLock() {
    boolean lockAcquired =
        lockHandler.executeWithLocks(
            this::createCustomMetricDescriptors,
            null,
            LOCK_LEASE_TIME,
            LOCK_NAME);

    if (lockAcquired) {
      isDescriptorInitialized.set(true);
    } else {
      // Another instance holds the lock. We skip creation
      logger.atInfo().log(
          "Lock '%s' in use. Another instance is handling metric creation. Skipping.", LOCK_NAME);
    }
  }
  // Defines the custom metrics in Cloud Monitoring
  private Void createCustomMetricDescriptors() {
    // 1. TLD Status Descriptor
    createMetricDescriptor(
        METRIC_TLD_STATUS,
        DISPLAY_NAME_TLD_STATUS,
        DESC_TLD_STATUS,
        "INT64",
        ImmutableList.of(LABEL_TLD));

    // 2. Service Status Descriptor
    createMetricDescriptor(
        METRIC_SERVICE_STATUS,
        DISPLAY_NAME_SERVICE_STATUS,
        DESC_SERVICE_STATUS,
        "INT64",
        ImmutableList.of(LABEL_TLD, LABEL_SERVICE_TYPE));

    // 3. Emergency Usage Descriptor
    createMetricDescriptor(
        METRIC_EMERGENCY_USAGE,
        DISPLAY_NAME_EMERGENCY_USAGE,
        DESC_EMERGENCY_USAGE,
        "DOUBLE",
        ImmutableList.of(LABEL_TLD, LABEL_SERVICE_TYPE));

    logger.atInfo().log("Metric descriptors ensured for project %s", projectId);
  }

  private void createMetricDescriptor(
      String metricTypeSuffix,
      String displayName,
      String description,
      String valueType,
      ImmutableList<String> labelKeys) {

    ImmutableList<LabelDescriptor> labelDescriptors =
        labelKeys.stream()
            .map(
                key ->
                    new LabelDescriptor()
                        .setKey(key)
                        .setValueType("STRING")
                        .setDescription(
                            key.equals(LABEL_TLD)
                                ? "The TLD being monitored"
                                : "The type of service"))
            .collect(toImmutableList());

    MetricDescriptor descriptor =
        new MetricDescriptor()
            .setType(METRIC_DOMAIN + metricTypeSuffix)
            .setMetricKind(GAUGE_METRIC_KIND)
            .setValueType(valueType)
            .setDisplayName(displayName)
            .setDescription(description)
            .setLabels(labelDescriptors);
    try {
      monitoringClient
          .projects()
          .metricDescriptors()
          .create(this.projectName, descriptor)
          .execute();
    } catch (GoogleJsonResponseException e) {
      if (e.getStatusCode() == METRICS_ALREADY_EXIST) {
        // the metric already exists. This is expected.
        logger.atFine().log("Metric descriptor %s already exists.", metricTypeSuffix);
      } else {
        logger.atWarning().withCause(e).log(
            "Failed to create metric descriptor %s. Status: %d",
            metricTypeSuffix, e.getStatusCode());
      }
    } catch (Exception e) {
      logger.atWarning().withCause(e).log(
          "Unexpected error creating metric descriptor %s.", metricTypeSuffix);
    }
  }

  private void pushBatchMetrics(ImmutableList<TldServiceState> states) {
    Instant now = Instant.ofEpochMilli(clock.nowUtc().getMillis());
    TimeInterval interval = new TimeInterval().setEndTime(now.toString());
    Stream<TimeSeries> allTimeSeriesStream =
        states.stream().flatMap(state -> createMetricsForState(state, interval));

    Iterator<List<TimeSeries>> batchIterator =
        Iterators.partition(allTimeSeriesStream.iterator(), MAX_TIMESERIES_PER_REQUEST);

    int successCount = 0;
    int failureCount = 0;

    // Iterate and count
    while (batchIterator.hasNext()) {
      List<TimeSeries> batch = batchIterator.next();
      try {
        CreateTimeSeriesRequest request = new CreateTimeSeriesRequest().setTimeSeries(batch);
        monitoringClient.projects().timeSeries().create(this.projectName, request).execute();

        successCount++;

      } catch (IOException e) {
        failureCount++;
        // Log individual batch failures, so we have the stack trace for debugging
        logger.atWarning().withCause(e).log(
            "Failed to push batch of %d time series.", batch.size());
      }
    }

    // 4. Log the final summary
    if (failureCount > 0) {
      logger.atWarning().log(
          "Metric push finished with errors. Batches Succeeded: %d, Failed: %d",
          successCount, failureCount);
    } else {
      logger.atInfo().log("Metric push finished successfully. Batches Succeeded: %d", successCount);
    }
  }

  /** Generates all TimeSeries (TLD + Services) for a single state object. */
  private Stream<TimeSeries> createMetricsForState(TldServiceState state, TimeInterval interval) {
    // 1. TLD Status
    Stream<TimeSeries> tldStream = Stream.of(createTldStatusTimeSeries(state, interval));

    // 2. Service Metrics (if any)
    Stream<TimeSeries> serviceStream =
        state.serviceStatuses().entrySet().stream()
            .flatMap(
                entry ->
                    createServiceMetricsStream(
                        state.tld(), entry.getKey(), entry.getValue(), interval));

    return Stream.concat(tldStream, serviceStream);
  }

  private Stream<TimeSeries> createServiceMetricsStream(
      String tld, String serviceType, ServiceStatus statusObj, TimeInterval interval) {
    ImmutableMap<String, String> labels =
        ImmutableMap.of(LABEL_TLD, tld, LABEL_SERVICE_TYPE, serviceType);

    return Stream.of(
        createTimeSeries(
            METRIC_SERVICE_STATUS, labels, parseServiceStatus(statusObj.status()), interval),
        createTimeSeries(METRIC_EMERGENCY_USAGE, labels, statusObj.emergencyThreshold(), interval));
  }

  private TimeSeries createTldStatusTimeSeries(TldServiceState state, TimeInterval interval) {
    return createTimeSeries(
        METRIC_TLD_STATUS,
        ImmutableMap.of(LABEL_TLD, state.tld()),
        parseTldStatus(state.status()),
        interval);
  }

  private TimeSeries createTimeSeries(
      String suffix, ImmutableMap<String, String> labels, Number val, TimeInterval interval) {
    Metric metric = new Metric().setType(METRIC_DOMAIN + suffix).setLabels(labels);

    TypedValue tv = new TypedValue();
    if (val instanceof Double) {
      tv.setDoubleValue((Double) val);
    } else {
      tv.setInt64Value(val.longValue());
    }

    return new TimeSeries()
        .setMetric(metric)
        .setResource(this.monitoredResource)
        .setPoints(ImmutableList.of(new Point().setInterval(interval).setValue(tv)));
  }

  /**
   * Translates MoSAPI status to a numeric metric.
   *
   * <p>Mappings: 1 (UP) = Healthy; 0 (DOWN) = Critical failure; 2 (UP-INCONCLUSIVE) = Disabled/Not
   * Monitored/In Maintenance.
   *
   * <p>A status of 2 indicates the SLA monitoring system is under maintenance. The TLD is
   * considered "UP" by default, but individual service checks are disabled. This distinguishes
   * maintenance windows from actual availability or outages.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Spec Sec 5.1</a>
   */
  private long parseTldStatus(String status) {
    return switch (Ascii.toUpperCase(status)) {
      case STATUS_DOWN -> 0;
      case STATUS_UP_INCONCLUSIVE -> 2;
      default -> 1; // status is up
    };
  }

  /**
   * Translates MoSAPI service status to a numeric metric.
   *
   * <p>Mappings: 1 (UP) = Healthy; 0 (DOWN) = Critical failure; 2 (DISABLED/UP-INCONCLUSIVE*) =
   * Disabled/Not Monitored/In Maintenance.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Spec Sec 5.1</a>
   */
  private long parseServiceStatus(String status) {
    String serviceStatus = Ascii.toUpperCase(status);
    if (serviceStatus.startsWith(STATUS_UP_INCONCLUSIVE)) {
      return 2;
    }
    return switch (serviceStatus) {
      case STATUS_DOWN -> 0;
      case STATUS_DISABLED -> 2;
      default -> 1; // status is Up
    };
  }
}
