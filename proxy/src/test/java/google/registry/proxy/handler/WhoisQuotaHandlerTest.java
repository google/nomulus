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

package google.registry.proxy.handler;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.proxy.Protocol.PROTOCOL_KEY;
import static google.registry.proxy.handler.ProxyProtocolHandler.REMOTE_ADDRESS_KEY;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import google.registry.proxy.Protocol;
import google.registry.proxy.handler.QuotaHandler.OverQuotaException;
import google.registry.proxy.handler.QuotaHandler.WhoisQuotaHandler;
import google.registry.proxy.metric.FrontendMetrics;
import google.registry.proxy.quota.QuotaManager;
import google.registry.proxy.quota.QuotaManager.QuotaRequest;
import google.registry.proxy.quota.QuotaManager.QuotaResponse;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link WhoisQuotaHandler} */
class WhoisQuotaHandlerTest {

  private final QuotaManager quotaManager = mock(QuotaManager.class);
  private final FrontendMetrics metrics = mock(FrontendMetrics.class);
  private final WhoisQuotaHandler handler = new WhoisQuotaHandler(quotaManager, metrics);
  private final EmbeddedChannel channel = new EmbeddedChannel(handler);
  private final DateTime now = DateTime.now(DateTimeZone.UTC);
  private final String remoteAddress = "127.0.0.1";
  private final Object message = new Object();

  private void setProtocol(Channel channel) {
    channel
        .attr(PROTOCOL_KEY)
        .set(
            Protocol.frontendBuilder()
                .name("whois")
                .port(12345)
                .handlerProviders(ImmutableList.of())
                .relayProtocol(
                    Protocol.backendBuilder()
                        .name("backend")
                        .host("host.tld")
                        .port(1234)
                        .handlerProviders(ImmutableList.of())
                        .build())
                .build());
  }

  @BeforeEach
  void beforeEach() {
    channel.attr(REMOTE_ADDRESS_KEY).set(remoteAddress);
    setProtocol(channel);
  }

  @Test
  void testSuccess_quotaGranted() {
    when(quotaManager.acquireQuota(QuotaRequest.create(remoteAddress)))
        .thenReturn(QuotaResponse.create(true, remoteAddress, now));

    // First read, acquire quota.
    assertThat(channel.writeInbound(message)).isTrue();
    assertThat((Object) channel.readInbound()).isEqualTo(message);
    assertThat(channel.isActive()).isTrue();
    verify(quotaManager).acquireQuota(QuotaRequest.create(remoteAddress));

    // Second read, should not acquire quota again.
    assertThat(channel.writeInbound(message)).isTrue();
    assertThat((Object) channel.readInbound()).isEqualTo(message);

    // Channel closed, release quota.
    ChannelFuture unusedFuture = channel.close();
    verifyNoMoreInteractions(quotaManager);
  }

  @Test
  void testFailure_quotaNotGranted() {
    when(quotaManager.acquireQuota(QuotaRequest.create(remoteAddress)))
        .thenReturn(QuotaResponse.create(false, remoteAddress, now));
    OverQuotaException e =
        assertThrows(OverQuotaException.class, () -> channel.writeInbound(message));
    assertThat(e).hasMessageThat().contains("none");
    verify(metrics).registerQuotaRejection("whois", "none");
    verifyNoMoreInteractions(metrics);
  }

  @Test
  void testSuccess_twoChannels_twoUserIds() {
    // Set up another user.
    final WhoisQuotaHandler otherHandler = new WhoisQuotaHandler(quotaManager, metrics);
    final EmbeddedChannel otherChannel = new EmbeddedChannel(otherHandler);
    final String otherRemoteAddress = "192.168.0.1";
    otherChannel.attr(REMOTE_ADDRESS_KEY).set(otherRemoteAddress);
    setProtocol(otherChannel);
    final DateTime later = now.plus(Duration.standardSeconds(1));

    when(quotaManager.acquireQuota(QuotaRequest.create(remoteAddress)))
        .thenReturn(QuotaResponse.create(true, remoteAddress, now));
    when(quotaManager.acquireQuota(QuotaRequest.create(otherRemoteAddress)))
        .thenReturn(QuotaResponse.create(false, otherRemoteAddress, later));

    // Allows the first user.
    assertThat(channel.writeInbound(message)).isTrue();
    assertThat((Object) channel.readInbound()).isEqualTo(message);
    assertThat(channel.isActive()).isTrue();

    // Blocks the second user.
    OverQuotaException e =
        assertThrows(OverQuotaException.class, () -> otherChannel.writeInbound(message));
    assertThat(e).hasMessageThat().contains("none");
    verify(metrics).registerQuotaRejection("whois", "none");
    verifyNoMoreInteractions(metrics);
  }

  @Test
  void testSuccess_oneUser_rateLimited() {
    // Set up another channel for the same user.
    final WhoisQuotaHandler otherHandler = new WhoisQuotaHandler(quotaManager, metrics);
    final EmbeddedChannel otherChannel = new EmbeddedChannel(otherHandler);
    otherChannel.attr(REMOTE_ADDRESS_KEY).set(remoteAddress);
    setProtocol(otherChannel);
    final DateTime later = now.plus(Duration.standardSeconds(1));

    // Set up the third channel for the same user
    final WhoisQuotaHandler thirdHandler = new WhoisQuotaHandler(quotaManager, metrics);
    final EmbeddedChannel thirdChannel = new EmbeddedChannel(thirdHandler);
    thirdChannel.attr(REMOTE_ADDRESS_KEY).set(remoteAddress);
    final DateTime evenLater = now.plus(Duration.standardSeconds(60));

    when(quotaManager.acquireQuota(QuotaRequest.create(remoteAddress)))
        .thenReturn(QuotaResponse.create(true, remoteAddress, now))
        // Throttles the second connection.
        .thenReturn(QuotaResponse.create(false, remoteAddress, later))
        // Allows the third connection because token refilled.
        .thenReturn(QuotaResponse.create(true, remoteAddress, evenLater));

    // Allows the first channel.
    assertThat(channel.writeInbound(message)).isTrue();
    assertThat((Object) channel.readInbound()).isEqualTo(message);
    assertThat(channel.isActive()).isTrue();

    // Blocks the second channel.
    OverQuotaException e =
        assertThrows(OverQuotaException.class, () -> otherChannel.writeInbound(message));
    assertThat(e).hasMessageThat().contains("none");
    verify(metrics).registerQuotaRejection("whois", "none");

    // Allows the third channel.
    assertThat(thirdChannel.writeInbound(message)).isTrue();
    assertThat((Object) thirdChannel.readInbound()).isEqualTo(message);
    assertThat(thirdChannel.isActive()).isTrue();
    verifyNoMoreInteractions(metrics);
  }
}
