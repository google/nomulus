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
import javax.inject.Inject;
import javax.inject.Singleton;
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

  @Inject
  BackendMetrics() {}

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
    requestsCounter.increment(protocol, certHash);
    requestBytes.record(bytes, protocol, certHash);
  }

  @NonFinalForTesting
  public void responseReceived(
      String protocol, String certHash, FullHttpResponse response, Duration latency) {
    latencyMs.record(latency.getMillis(), protocol, certHash);
    responseBytes.record(response.content().readableBytes(), protocol, certHash);
    responsesCounter.increment(protocol, certHash, response.status().toString());
  }
}
