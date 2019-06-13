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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.monitoring.metrics.IncrementableMetric;
import com.google.monitoring.metrics.LabelDescriptor;
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

/** Frontend metrics instrumentation. */
@Singleton
public class FrontendMetrics {

  /**
   * Labels to register front metrics with.
   *
   * <p>The client certificate hash value is only used for EPP metrics. For WHOIS metrics, it will
   * always be {@code "none"}. In order to get the actual registrar name, one can use the {@code
   * nomulus} tool:
   *
   * <pre>
   * nomulus -e production list_registrars -f clientCertificateHash | grep $HASH
   * </pre>
   */
  private static final ImmutableSet<LabelDescriptor> LABELS =
      ImmutableSet.of(
          LabelDescriptor.create("protocol", "Name of the protocol."),
          LabelDescriptor.create(
              "client_cert_hash", "SHA256 hash of the client certificate, if available."));

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
                  activeConnections
                      .entrySet()
                      .stream()
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

  @Inject
  public FrontendMetrics() {}

  /**
   * Resets all frontend metrics.
   *
   * <p>This should only be used in tests to reset states. Production code should not call this
   * method.
   */
  @VisibleForTesting
  void resetMetrics() {
    totalConnectionsCounter.reset();
    activeConnections.clear();
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
}
