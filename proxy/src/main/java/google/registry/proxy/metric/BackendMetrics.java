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

package google.registry.proxy.metric;

import com.google.common.collect.ImmutableSet;
import com.google.monitoring.metrics.EventMetric;
import com.google.monitoring.metrics.IncrementableMetric;
import com.google.monitoring.metrics.LabelDescriptor;
import com.google.monitoring.metrics.MetricRegistryImpl;
import google.registry.util.NonFinalForTesting;
import io.netty.handler.codec.http.FullHttpResponse;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.Random;
import org.joda.time.Duration;

/** Backend metrics instrumentation. */
@Singleton
public class BackendMetrics extends BaseMetrics {

  static final IncrementableMetric requestsCounter =
      MetricRegistryImpl.getDefault()
          .newIncrementableMetric(
              "/proxy/backend/requests",
              "Total number of requests send to the backend.",
              "Requests",
              LABELS);

  static final IncrementableMetric responsesCounter =
      MetricRegistryImpl.getDefault()
          .newIncrementableMetric(
              "/proxy/backend/responses",
              "Total number of responses received by the backend.",
              "Responses",
              ImmutableSet.<LabelDescriptor>builder()
                  .addAll(LABELS)
                  .add(LabelDescriptor.create("status", "HTTP status code."))
                  .build());

  static final EventMetric requestBytes =
      MetricRegistryImpl.getDefault()
          .newEventMetric(
              "/proxy/backend/request_bytes",
              "Size of the backend requests sent.",
              "Request Bytes",
              LABELS,
              DEFAULT_SIZE_FITTER);

  static final EventMetric responseBytes =
      MetricRegistryImpl.getDefault()
          .newEventMetric(
              "/proxy/backend/response_bytes",
              "Size of the backend responses received.",
              "Response Bytes",
              LABELS,
              DEFAULT_SIZE_FITTER);

  static final EventMetric latencyMs =
      MetricRegistryImpl.getDefault()
          .newEventMetric(
              "/proxy/backend/latency_ms",
              "Round-trip time between a request sent and its corresponding response received.",
              "Latency Milliseconds",
              LABELS,
              DEFAULT_LATENCY_FITTER);

  private final Random random;
  private final double backendMetricsRatio;

  @Inject
  BackendMetrics(@Named("backendMetricsRatio") double backendMetricsRatio, Random random) {
    this.backendMetricsRatio = backendMetricsRatio;
    this.random = random;
  }

  @Override
  void resetMetrics() {
    requestBytes.reset();
    requestsCounter.reset();
    responseBytes.reset();
    responsesCounter.reset();
    latencyMs.reset();
  }

  @NonFinalForTesting
  public void requestSent(String protocol, String certHash, int bytes) {
    // Short-circuit metrics recording randomly according to the configured ratio.
    if (random.nextDouble() > backendMetricsRatio) {
      return;
    }
    requestsCounter.incrementBy(roundRatioReciprocal(), protocol, certHash);
    requestBytes.record(bytes, protocol, certHash);
  }

  @NonFinalForTesting
  public void responseReceived(
      String protocol, String certHash, FullHttpResponse response, Duration latency) {
    // Short-circuit metrics recording randomly according to the configured ratio.
    if (random.nextDouble() > backendMetricsRatio) {
      return;
    }
    latencyMs.record(latency.getMillis(), protocol, certHash);
    responseBytes.record(response.content().readableBytes(), protocol, certHash);
    responsesCounter.incrementBy(
        roundRatioReciprocal(), protocol, certHash, response.status().toString());
  }

  /**
   * Returns the reciprocal of the backend metrics ratio, stochastically rounded to the nearest int.
   *
   * <p>This is necessary because if we are only going to record a metric, say, 1/20th of the time,
   * then each time we do record it, we should increment it by 20 so that, modulo some randomness,
   * the total figures still add up to the same amount.
   *
   * <p>The stochastic rounding is necessary to prevent introducing errors stemming from rounding a
   * non-integer reciprocal consistently to the floor or ceiling. As an example, if the ratio is
   * .03, then the reciprocal would be 33.3..., so two-thirds of the time it should increment by 33
   * and one-third of the time it should increment by 34, calculated randomly, so that the overall
   * total adds up correctly.
   */
  private long roundRatioReciprocal() {
    double reciprocal = 1 / backendMetricsRatio;
    return (long)
        ((random.nextDouble() < reciprocal - Math.floor(reciprocal))
            ? Math.ceil(reciprocal)
            : Math.floor(reciprocal));
  }
}
