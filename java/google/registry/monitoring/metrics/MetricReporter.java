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
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AbstractScheduledService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.inject.Inject;
import javax.inject.Named;

/**
 * Engine to write metrics to a {@link MetricWriter} on a regular periodic basis.
 *
 * <p>In the Producer/Consumer pattern, this class is the Producer and {@link MetricExporter} is the
 * consumer.
 */
public class MetricReporter extends AbstractScheduledService {

  private static final Logger logger = Logger.getLogger(MetricReporter.class.getName());

  private final long writeInterval;
  private final MetricRegistry metricRegistry;
  private final BlockingQueue<Optional<ImmutableList<MetricPoint<?>>>> writeQueue;
  private MetricExporter metricExporter;
  private final MetricWriter metricWriter;
  private final ThreadFactory threadFactory;

  /**
   * Returns a new MetricReporter.
   *
   * @param metricWriter {@link MetricWriter} implementation to write metrics to.
   * @param writeInterval time period between metric writes, in seconds.
   * @param threadFactory factory to use when creating background threads.
   */
  @Inject
  public MetricReporter(
      MetricWriter metricWriter,
      @Named("metricsWriteInterval") long writeInterval,
      @Named("metricsBackgroundThreadFactory") ThreadFactory threadFactory) {
    this(
        metricWriter,
        writeInterval,
        threadFactory,
        MetricRegistryImpl.getDefault(),
        new ArrayBlockingQueue<Optional<ImmutableList<MetricPoint<?>>>>(1000));
  }

  @VisibleForTesting
  MetricReporter(
      MetricWriter metricWriter,
      long writeInterval,
      ThreadFactory threadFactory,
      MetricRegistry metricRegistry,
      BlockingQueue<Optional<ImmutableList<MetricPoint<?>>>> writeQueue) {
    checkArgument(writeInterval > 0, "writeInterval must be greater than zero");

    this.metricWriter = metricWriter;
    this.writeInterval = writeInterval;
    this.threadFactory = threadFactory;
    this.metricRegistry = metricRegistry;
    this.writeQueue = writeQueue;
    this.metricExporter = new MetricExporter(writeQueue, metricWriter, threadFactory);
  }

  @Override
  protected void runOneIteration() {
    logger.info("Running background metric push");

    if (metricExporter.state() == State.FAILED) {
      startMetricExporter();
    }

    ImmutableList.Builder<MetricPoint<?>> points = new ImmutableList.Builder<>();

    /*
    TODO(shikhman): Right now timestamps are recorded for each datapoint, which may use more storage
    on the backend than if one timestamp were recorded for a batch. This should be configurable.
     */
    for (Metric<?> metric : metricRegistry.getRegisteredMetrics()) {
      points.addAll(metric.getTimestampedValues());
      logger.fine(String.format("Enqueued metric %s", metric));
      MetricMetrics.pushedPoints.increment(
          metric.getMetricSchema().kind().name(), metric.getValueClass().toString());
    }

    if (!writeQueue.offer(Optional.of(points.build()))) {
      logger.severe("writeQueue full, dropped a reporting interval of points");
    }

    MetricMetrics.pushIntervals.increment();
  }

  @Override
  protected void shutDown() {
    // Make sure to run one iteration on shutdown so that short-lived programs still report at
    // least once.
    runOneIteration();

    // Offer a poision pill to inform the exporter to stop.
    writeQueue.offer(Optional.<ImmutableList<MetricPoint<?>>>absent());
    try {
      metricExporter.awaitTerminated(10, TimeUnit.SECONDS);
      logger.info("Shut down MetricExporter");
    } catch (IllegalStateException exception) {
      logger.log(
          Level.SEVERE,
          "Failed to shut down MetricExporter because it was FAILED",
          metricExporter.failureCause());
    } catch (TimeoutException exception) {
      logger.log(Level.SEVERE, "Failed to shut down MetricExporter within the timeout", exception);
    }
  }

  @Override
  protected void startUp() {
    startMetricExporter();
  }

  @Override
  protected Scheduler scheduler() {
    // Start writing after waiting for one writeInterval.
    return Scheduler.newFixedDelaySchedule(writeInterval, writeInterval, TimeUnit.SECONDS);
  }

  @Override
  protected ScheduledExecutorService executor() {
    final ScheduledExecutorService executor =
        Executors.newSingleThreadScheduledExecutor(threadFactory);
    // Make sure the ExecutorService terminates when this service does.
    addListener(
        new Listener() {
          @Override
          public void terminated(State from) {
            executor.shutdown();
          }

          @Override
          public void failed(State from, Throwable failure) {
            executor.shutdown();
          }
        },
        directExecutor());
    return executor;
  }

  private void startMetricExporter() {
    switch (metricExporter.state()) {
      case NEW:
        metricExporter.startAsync();
        break;
      case FAILED:
        logger.log(
            Level.SEVERE,
            "MetricExporter died unexpectedly, restarting",
            metricExporter.failureCause());
        this.metricExporter = new MetricExporter(writeQueue, metricWriter, threadFactory);
        this.metricExporter.startAsync();
        break;
      default:
        throw new IllegalStateException(
            "MetricExporter not FAILED or NEW, should not be calling startMetricExporter");
    }
  }
}
