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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.monitoring.metrics.EventMetric;
import com.google.monitoring.metrics.IncrementableMetric;
import com.google.monitoring.metrics.Metric;
import com.google.monitoring.metrics.MetricRegistryImpl;
import google.registry.util.NonFinalForTesting;
import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.joda.time.Duration;

/** Frontend metrics instrumentation. */
@Singleton
public class FrontendMetrics extends BaseMetrics {

  private static final ConcurrentMap<ImmutableList<String>, ChannelGroup> activeConnections =
      new ConcurrentHashMap<>();

  static final Metric<Long> activeConnectionsGauge =
      MetricRegistryImpl.getDefault()
          .newGauge(
              "/proxy/frontend/active_connections",
              "Number of active connections from clients to the proxy.",
              "Active Connections",
              LABELS,
              () ->
                  activeConnections.entrySet().stream()
                      .collect(
                          ImmutableMap.toImmutableMap(
                              Map.Entry::getKey, entry -> (long) entry.getValue().size())),
              Long.class);

  static final IncrementableMetric totalConnectionsCounter =
      MetricRegistryImpl.getDefault()
          .newIncrementableMetric(
              "/proxy/frontend/total_connections",
              "Total number connections ever made from clients to the proxy.",
              "Total Connections",
              LABELS);

  static final IncrementableMetric quotaRejectionsCounter =
      MetricRegistryImpl.getDefault()
          .newIncrementableMetric(
              "/proxy/frontend/quota_rejections",
              "Total number rejected quota request made by proxy for each connection.",
              "Quota Rejections",
              LABELS);

  static final EventMetric latencyMs =
      MetricRegistryImpl.getDefault()
          .newEventMetric(
              "/proxy/frontend/latency_ms",
              "Round-trip time between a request received and its corresponding response is sent.",
              "Latency Milliseconds",
              LABELS,
              DEFAULT_LATENCY_FITTER);

  @Inject
  public FrontendMetrics() {}

  @Override
  void resetMetrics() {
    totalConnectionsCounter.reset();
    activeConnections.clear();
    latencyMs.reset();
  }

  @NonFinalForTesting
  public void registerActiveConnection(String protocol, String certHash, Channel channel) {
    totalConnectionsCounter.increment(protocol, certHash);
    ImmutableList<String> labels = ImmutableList.of(protocol, certHash);
    ChannelGroup channelGroup;
    if (activeConnections.containsKey(labels)) {
      channelGroup = activeConnections.get(labels);
    } else {
      channelGroup = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
      activeConnections.put(labels, channelGroup);
    }
    channelGroup.add(channel);
  }

  @NonFinalForTesting
  public void registerQuotaRejection(String protocol, String certHash) {
    quotaRejectionsCounter.increment(protocol, certHash);
  }

  @NonFinalForTesting
  public void responseSent(String protocol, String certHash, Duration latency) {
    latencyMs.record(latency.getMillis(), protocol, certHash);
  }
}
