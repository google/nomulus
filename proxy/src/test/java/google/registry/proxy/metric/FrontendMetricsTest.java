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

import static com.google.common.truth.Truth.assertThat;
import static com.google.monitoring.metrics.contrib.LongMetricSubject.assertThat;

import io.netty.channel.ChannelFuture;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FrontendMetrics}. */
class FrontendMetricsTest {

  private static final String PROTOCOL = "some protocol";
  private static final String CERT_HASH = "abc_blah_1134zdf";
  private final FrontendMetrics metrics = new FrontendMetrics();

  @BeforeEach
  void beforeEach() {
    metrics.resetMetrics();
  }

  @Test
  void testSuccess_oneConnection() {
    EmbeddedChannel channel = new EmbeddedChannel();
    metrics.registerActiveConnection(PROTOCOL, CERT_HASH, channel);
    assertThat(channel.isActive()).isTrue();
    assertThat(FrontendMetrics.activeConnectionsGauge)
        .hasValueForLabels(1, PROTOCOL, CERT_HASH)
        .and()
        .hasNoOtherValues();
    assertThat(FrontendMetrics.totalConnectionsCounter)
        .hasValueForLabels(1, PROTOCOL, CERT_HASH)
        .and()
        .hasNoOtherValues();

    ChannelFuture unusedFuture = channel.close();
    assertThat(channel.isActive()).isFalse();
    assertThat(FrontendMetrics.activeConnectionsGauge).hasNoOtherValues();
    assertThat(FrontendMetrics.totalConnectionsCounter)
        .hasValueForLabels(1, PROTOCOL, CERT_HASH)
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testSuccess_twoConnections_sameClient() {
    EmbeddedChannel channel1 = new EmbeddedChannel();
    EmbeddedChannel channel2 = new EmbeddedChannel(DefaultChannelId.newInstance());

    metrics.registerActiveConnection(PROTOCOL, CERT_HASH, channel1);
    assertThat(channel1.isActive()).isTrue();
    assertThat(FrontendMetrics.activeConnectionsGauge)
        .hasValueForLabels(1, PROTOCOL, CERT_HASH)
        .and()
        .hasNoOtherValues();
    assertThat(FrontendMetrics.totalConnectionsCounter)
        .hasValueForLabels(1, PROTOCOL, CERT_HASH)
        .and()
        .hasNoOtherValues();

    metrics.registerActiveConnection(PROTOCOL, CERT_HASH, channel2);
    assertThat(channel2.isActive()).isTrue();
    assertThat(FrontendMetrics.activeConnectionsGauge)
        .hasValueForLabels(2, PROTOCOL, CERT_HASH)
        .and()
        .hasNoOtherValues();
    assertThat(FrontendMetrics.totalConnectionsCounter)
        .hasValueForLabels(2, PROTOCOL, CERT_HASH)
        .and()
        .hasNoOtherValues();

    @SuppressWarnings("unused")
    ChannelFuture unusedFuture1 = channel1.close();
    assertThat(channel1.isActive()).isFalse();
    assertThat(FrontendMetrics.activeConnectionsGauge)
        .hasValueForLabels(1, PROTOCOL, CERT_HASH)
        .and()
        .hasNoOtherValues();
    assertThat(FrontendMetrics.totalConnectionsCounter)
        .hasValueForLabels(2, PROTOCOL, CERT_HASH)
        .and()
        .hasNoOtherValues();

    @SuppressWarnings("unused")
    ChannelFuture unusedFuture2 = channel2.close();
    assertThat(channel2.isActive()).isFalse();
    assertThat(FrontendMetrics.activeConnectionsGauge).hasNoOtherValues();
    assertThat(FrontendMetrics.totalConnectionsCounter)
        .hasValueForLabels(2, PROTOCOL, CERT_HASH)
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testSuccess_twoConnections_differentClients() {
    EmbeddedChannel channel1 = new EmbeddedChannel();
    EmbeddedChannel channel2 = new EmbeddedChannel(DefaultChannelId.newInstance());
    String certHash2 = "blahblah_lol_234";

    metrics.registerActiveConnection(PROTOCOL, CERT_HASH, channel1);
    assertThat(channel1.isActive()).isTrue();
    assertThat(FrontendMetrics.activeConnectionsGauge)
        .hasValueForLabels(1, PROTOCOL, CERT_HASH)
        .and()
        .hasNoOtherValues();
    assertThat(FrontendMetrics.totalConnectionsCounter)
        .hasValueForLabels(1, PROTOCOL, CERT_HASH)
        .and()
        .hasNoOtherValues();

    metrics.registerActiveConnection(PROTOCOL, certHash2, channel2);
    assertThat(channel2.isActive()).isTrue();
    assertThat(FrontendMetrics.activeConnectionsGauge)
        .hasValueForLabels(1, PROTOCOL, CERT_HASH)
        .and()
        .hasValueForLabels(1, PROTOCOL, certHash2)
        .and()
        .hasNoOtherValues();
    assertThat(FrontendMetrics.totalConnectionsCounter)
        .hasValueForLabels(1, PROTOCOL, CERT_HASH)
        .and()
        .hasValueForLabels(1, PROTOCOL, certHash2)
        .and()
        .hasNoOtherValues();

    ChannelFuture unusedFuture = channel1.close();
    assertThat(channel1.isActive()).isFalse();
    assertThat(FrontendMetrics.activeConnectionsGauge)
        .hasValueForLabels(1, PROTOCOL, certHash2)
        .and()
        .hasNoOtherValues();
    assertThat(FrontendMetrics.totalConnectionsCounter)
        .hasValueForLabels(1, PROTOCOL, CERT_HASH)
        .and()
        .hasValueForLabels(1, PROTOCOL, certHash2)
        .and()
        .hasNoOtherValues();

    unusedFuture = channel2.close();
    assertThat(channel2.isActive()).isFalse();
    assertThat(FrontendMetrics.activeConnectionsGauge).hasNoOtherValues();
    assertThat(FrontendMetrics.totalConnectionsCounter)
        .hasValueForLabels(1, PROTOCOL, CERT_HASH)
        .and()
        .hasValueForLabels(1, PROTOCOL, certHash2)
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testSuccess_registerQuotaRejections() {
    String otherCertHash = "foobar1234X";
    String remoteAddress = "127.0.0.1";
    String otherProtocol = "other protocol";
    metrics.registerQuotaRejection(PROTOCOL, CERT_HASH);
    metrics.registerQuotaRejection(PROTOCOL, otherCertHash);
    metrics.registerQuotaRejection(PROTOCOL, otherCertHash);
    metrics.registerQuotaRejection(otherProtocol, remoteAddress);
    assertThat(FrontendMetrics.quotaRejectionsCounter)
        .hasValueForLabels(1, PROTOCOL, CERT_HASH)
        .and()
        .hasValueForLabels(2, PROTOCOL, otherCertHash)
        .and()
        .hasValueForLabels(1, otherProtocol, remoteAddress)
        .and()
        .hasNoOtherValues();
  }
}
