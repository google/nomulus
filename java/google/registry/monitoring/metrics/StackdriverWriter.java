// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.monitoring.metrics;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.RateLimiter;
import google.registry.monitoring.metrics.MetricSchema.Kind;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Queue;
import java.util.logging.Logger;
import javax.annotation.concurrent.NotThreadSafe;
import javax.inject.Inject;
import javax.inject.Named;
import org.joda.time.Interval;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

/**
 * Metrics writer for Google Cloud Monitoring V3
 *
 * <p>This class communicates with the API via HTTP. In order to increase throughput and minimize
 * CPU, it buffers points to be written until it has {@code maxPointsPerRequest} points buffered or
 * until {@link #flush()} is called.
 *
 * @see <a href="Stackdriver API Introduction">https://cloud.google.com/monitoring/api/v3/</a>
 */
// TODO(shikhman): add retry logic
@NotThreadSafe
public class StackdriverWriter implements MetricWriter {

  /**
   * A counter representing the total number of points pushed. Has {@link MetricSchema.Kind} and
   * metric value types as labels.
   */
  private static final IncrementableMetric pushedPoints =
      MetricRegistryImpl.getDefault()
          .newIncrementableMetric(
              "/metrics/stackdriver/points_pushed",
              "Count of points pushed to Stackdriver Monitoring API.",
              "Points Pushed",
              ImmutableSet.of(
                  google.registry.monitoring.metrics.LabelDescriptor.create("kind", "Metric Kind"),
                  google.registry.monitoring.metrics.LabelDescriptor.create(
                      "valueType", "Metric Value Type")));
  private static final String METRIC_DOMAIN = "custom.googleapis.com";
  private static final String LABEL_VALUE_TYPE = "STRING";
  private static final DateTimeFormatter DATETIME_FORMATTER = ISODateTimeFormat.dateTime();
  private static final Logger logger = Logger.getLogger(StackdriverWriter.class.getName());
  // A map of native type to the equivalent Stackdriver metric type.
  private static final ImmutableMap<Class<?>, String> ENCODED_METRIC_TYPES =
      new ImmutableMap.Builder<Class<?>, String>()
          .put(Long.class, "INT64")
          .put(Double.class, "DOUBLE")
          .put(Boolean.class, "BOOL")
          .put(String.class, "STRING")
          .build();
  // A map of native kind to the equivalent Stackdriver metric kind.
  private static final ImmutableMap<String, String> ENCODED_METRIC_KINDS =
      new ImmutableMap.Builder<String, String>()
          .put(Kind.GAUGE.name(), "GAUGE")
          .put(Kind.CUMULATIVE.name(), "CUMULATIVE")
          .build();
  private static final String FLUSH_OVERFLOW_ERROR = "Cannot flush more than 200 points at a time";
  private static final String METRIC_KIND_ERROR =
      "Unrecognized metric kind, must be one of "
          + Joiner.on(",").join(ENCODED_METRIC_KINDS.keySet());
  private static final String METRIC_TYPE_ERROR =
      "Unrecognized metric type, must be one of "
          + Joiner.on(" ").join(ENCODED_METRIC_TYPES.keySet());
  private static final String METRIC_LABEL_COUNT_ERROR =
      "Metric label value count does not match its MetricDescriptor's label count";

  private final MonitoredResource monitoredResource;
  private final Queue<TimeSeries> timeSeriesBuffer;
  /**
   * A local cache of MetricDescriptors. A metric's metadata (name, kind, type, label definitions)
   * must be registered before points for the metric can be pushed.
   */
  private final HashMap<google.registry.monitoring.metrics.Metric<?>, MetricDescriptor>
      registeredDescriptors = new HashMap<>();
  private final String projectResource;
  private final Monitoring monitoringClient;
  private final int maxPointsPerRequest;
  private final RateLimiter rateLimiter;

  /**
   * Constructs a StackdriverWriter.
   *
   * <p>The monitoringClient must have read and write permissions to the Cloud Monitoring API v3 on
   * the provided project.
   */
  @Inject
  public StackdriverWriter(
      Monitoring monitoringClient,
      String project,
      MonitoredResource monitoredResource,
      @Named("stackdriverMaxQps") int maxQps,
      @Named("stackdriverMaxPointsPerRequest") int maxPointsPerRequest) {
    this.monitoringClient = checkNotNull(monitoringClient);
    this.projectResource = "projects/" + checkNotNull(project);
    this.monitoredResource = monitoredResource;
    this.maxPointsPerRequest = maxPointsPerRequest;
    this.timeSeriesBuffer = new ArrayDeque<>(maxPointsPerRequest);
    this.rateLimiter = RateLimiter.create(maxQps);
  }

  @VisibleForTesting
  static ImmutableList<LabelDescriptor> createLabelDescriptors(
      ImmutableSet<google.registry.monitoring.metrics.LabelDescriptor> labelDescriptors) {
    List<LabelDescriptor> stackDriverLabelDescriptors = new ArrayList<>(labelDescriptors.size());

    for (google.registry.monitoring.metrics.LabelDescriptor labelDescriptor : labelDescriptors) {
      stackDriverLabelDescriptors.add(
          new LabelDescriptor()
              .setKey(labelDescriptor.name())
              .setDescription(labelDescriptor.description())
              .setValueType(LABEL_VALUE_TYPE));
    }

    return ImmutableList.copyOf(stackDriverLabelDescriptors);
  }

  @VisibleForTesting
  static <V> MetricDescriptor createMetricDescriptor(
      google.registry.monitoring.metrics.Metric<V> metric) {
    return new MetricDescriptor()
        .setType(METRIC_DOMAIN + metric.getMetricSchema().name())
        .setDescription(metric.getMetricSchema().description())
        .setDisplayName(metric.getMetricSchema().valueDisplayName())
        .setValueType(ENCODED_METRIC_TYPES.get(metric.getValueClass()))
        .setLabels(createLabelDescriptors(metric.getMetricSchema().labels()))
        .setMetricKind(ENCODED_METRIC_KINDS.get(metric.getMetricSchema().kind().name()));
  }

  /** Encodes and writes a metric point to Stackdriver. The point may be buffered. */
  @Override
  public <V> void write(google.registry.monitoring.metrics.MetricPoint<V> point)
      throws IOException {
    checkNotNull(point);

    TimeSeries timeSeries = getEncodedTimeSeries(point);
    timeSeriesBuffer.add(timeSeries);

    logger.fine(String.format("Enqueued metric %s for writing", timeSeries.getMetric().getType()));
    if (timeSeriesBuffer.size() == maxPointsPerRequest) {
      flush();
    }
  }

  /** Flushes all buffered metric points to Stackdriver. This call is blocking. */
  @Override
  public void flush() throws IOException {
    checkState(timeSeriesBuffer.size() <= 200, FLUSH_OVERFLOW_ERROR);

    ImmutableList<TimeSeries> timeSeriesList = ImmutableList.copyOf(timeSeriesBuffer);
    timeSeriesBuffer.clear();

    CreateTimeSeriesRequest request = new CreateTimeSeriesRequest().setTimeSeries(timeSeriesList);

    rateLimiter.acquire();
    monitoringClient.projects().timeSeries().create(projectResource, request).execute();

    for (TimeSeries timeSeries : timeSeriesList) {
      pushedPoints.increment(timeSeries.getMetricKind(), timeSeries.getValueType());
    }
    logger.info(String.format("Flushed %d metrics to Stackdriver", timeSeriesList.size()));
  }

  /**
   * Registers a metric's {@link MetricDescriptor} with the Monitoring API.
   *
   * @param metric the metric to be registered.
   * @see <a href="Stackdriver MetricDescriptor
   *     API">https://cloud.google.com/monitoring/api/ref_v3/rest/v3/projects.metricDescriptors</a>
   */
  @VisibleForTesting
  MetricDescriptor registerMetric(final google.registry.monitoring.metrics.Metric<?> metric)
      throws IOException {
    if (registeredDescriptors.containsKey(metric)) {
      logger.fine(
          String.format("Using existing metric descriptor %s", metric.getMetricSchema().name()));
      return registeredDescriptors.get(metric);
    }

    MetricDescriptor descriptor = createMetricDescriptor(metric);

    rateLimiter.acquire();
    // We try to create a descriptor, but it may have been created already, so we re-fetch it on
    // failure
    try {
      descriptor =
          monitoringClient
              .projects()
              .metricDescriptors()
              .create(projectResource, descriptor)
              .execute();
      logger.info(String.format("Registered new metric descriptor %s", descriptor.getType()));
    } catch (GoogleJsonResponseException jsonException) {
      // Not the error we were expecting, just give up
      if (!"ALREADY_EXISTS".equals(jsonException.getStatusMessage())) {
        throw jsonException;
      }

      descriptor =
          monitoringClient
              .projects()
              .metricDescriptors()
              .get(projectResource + "/metricDescriptors/" + descriptor.getType())
              .execute();

      logger.info(
          String.format("Fetched existing metric descriptor %s", metric.getMetricSchema().name()));
    }

    registeredDescriptors.put(metric, descriptor);

    return descriptor;
  }

  private static TimeInterval encodeTimeInterval(Interval nativeInterval) {
    return new TimeInterval()
        .setEndTime(DATETIME_FORMATTER.print(nativeInterval.getEnd()))
        .setStartTime(DATETIME_FORMATTER.print(nativeInterval.getStart()));
  }

  /**
   * Encodes a {@link MetricPoint} into a Stackdriver {@link TimeSeries}.
   *
   * <p>This method will register the underlying {@link google.registry.monitoring.metrics.Metric}
   * as a Stackdriver {@link MetricDescriptor}.
   *
   * @see <a href="Stackdriver TimeSeries
   *     API">https://cloud.google.com/monitoring/api/ref_v3/rest/v3/TimeSeries</a>
   */
  @VisibleForTesting
  <V> TimeSeries getEncodedTimeSeries(google.registry.monitoring.metrics.MetricPoint<V> point)
      throws IOException {
    google.registry.monitoring.metrics.Metric<V> metric = point.metric();
    try {
      checkArgument(
          ENCODED_METRIC_KINDS.containsKey(metric.getMetricSchema().kind().name()),
          METRIC_KIND_ERROR);
      checkArgument(ENCODED_METRIC_TYPES.containsKey(metric.getValueClass()), METRIC_TYPE_ERROR);
    } catch (IllegalArgumentException e) {
      throw new IOException(e.getMessage());
    }

    MetricDescriptor descriptor = registerMetric(metric);

    if (point.labelValues().size() != point.metric().getMetricSchema().labels().size()) {
      throw new IOException(METRIC_LABEL_COUNT_ERROR);
    }

    V value = point.value();
    TypedValue encodedValue = new TypedValue();
    Class<?> valueClass = metric.getValueClass();

    if (valueClass == Long.class) {
      encodedValue.setInt64Value((Long) value);
    } else if (valueClass == Double.class) {
      encodedValue.setDoubleValue((Double) value);
    } else if (valueClass == Boolean.class) {
      encodedValue.setBoolValue((Boolean) value);
    } else if (valueClass == String.class) {
      encodedValue.setStringValue((String) value);
    } else {
      // This is unreachable because the precondition checks will catch all NotSerializable
      // exceptions.
      throw new IllegalArgumentException("Invalid metric valueClass: " + metric.getValueClass());
    }

    Point encodedPoint =
        new Point().setInterval(encodeTimeInterval(point.interval())).setValue(encodedValue);

    List<LabelDescriptor> encodedLabels = descriptor.getLabels();
    // The MetricDescriptors returned by the GCM API have null fields rather than empty lists
    encodedLabels = encodedLabels == null ? ImmutableList.<LabelDescriptor>of() : encodedLabels;

    ImmutableMap.Builder<String, String> labelValues = new ImmutableMap.Builder<>();
    int i = 0;
    for (LabelDescriptor labelDescriptor : encodedLabels) {
      labelValues.put(labelDescriptor.getKey(), point.labelValues().get(i++));
    }

    Metric encodedMetric =
        new Metric().setType(descriptor.getType()).setLabels(labelValues.build());

    return new TimeSeries()
        .setMetric(encodedMetric)
        .setPoints(ImmutableList.of(encodedPoint))
        .setResource(monitoredResource)
        // these two attributes are ignored by the API, we set them here to use elsewhere
        // for internal metrics.
        .setMetricKind(descriptor.getMetricKind())
        .setValueType(descriptor.getValueType());
  }
}
