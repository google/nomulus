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
import static google.registry.proxy.handler.EppServiceHandler.CLIENT_CERTIFICATE_HASH_KEY;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import google.registry.proxy.Protocol;
import google.registry.proxy.handler.QuotaHandler.EppQuotaHandler;
import google.registry.proxy.handler.QuotaHandler.OverQuotaException;
import google.registry.proxy.metric.FrontendMetrics;
import google.registry.proxy.quota.QuotaManager;
import google.registry.proxy.quota.QuotaManager.QuotaRebate;
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

/** Unit tests for {@link EppQuotaHandler} */
class EppQuotaHandlerTest {

  private final QuotaManager quotaManager = mock(QuotaManager.class);
  private final FrontendMetrics metrics = mock(FrontendMetrics.class);
  private final EppQuotaHandler handler = new EppQuotaHandler(quotaManager, metrics);
  private final EmbeddedChannel channel = new EmbeddedChannel(handler);
  private final String clientCertHash = "blah/123!";
  private final DateTime now = DateTime.now(DateTimeZone.UTC);
  private final Object message = new Object();

  private void setProtocol(Channel channel) {
    channel
        .attr(PROTOCOL_KEY)
        .set(
            Protocol.frontendBuilder()
                .name("epp")
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
    channel.attr(CLIENT_CERTIFICATE_HASH_KEY).set(clientCertHash);
    setProtocol(channel);
  }

  @Test
  void testSuccess_quotaGrantedAndReturned() {
    when(quotaManager.acquireQuota(QuotaRequest.create(clientCertHash)))
        .thenReturn(QuotaResponse.create(true, clientCertHash, now));

    // First read, acquire quota.
    assertThat(channel.writeInbound(message)).isTrue();
    assertThat((Object) channel.readInbound()).isEqualTo(message);
    assertThat(channel.isActive()).isTrue();
    verify(quotaManager).acquireQuota(QuotaRequest.create(clientCertHash));

    // Second read, should not acquire quota again.
    Object newMessage = new Object();
    assertThat(channel.writeInbound(newMessage)).isTrue();
    assertThat((Object) channel.readInbound()).isEqualTo(newMessage);
    verifyNoMoreInteractions(quotaManager);

    // Channel closed, release quota.
    ChannelFuture unusedFuture = channel.close();
    verify(quotaManager)
        .releaseQuota(QuotaRebate.create(QuotaResponse.create(true, clientCertHash, now)));
    verifyNoMoreInteractions(quotaManager);
  }

  @Test
  void testFailure_quotaNotGranted() {
    when(quotaManager.acquireQuota(QuotaRequest.create(clientCertHash)))
        .thenReturn(QuotaResponse.create(false, clientCertHash, now));
    OverQuotaException e =
        assertThrows(OverQuotaException.class, () -> channel.writeInbound(message));
    ChannelFuture unusedFuture = channel.close();
    assertThat(e).hasMessageThat().contains(clientCertHash);
    verify(quotaManager).acquireQuota(QuotaRequest.create(clientCertHash));
    // Make sure that quotaManager.releaseQuota() is not called when the channel closes.
    verifyNoMoreInteractions(quotaManager);
    verify(metrics).registerQuotaRejection("epp", clientCertHash);
    verifyNoMoreInteractions(metrics);
  }

  @Test
  void testSuccess_twoChannels_twoUserIds() {
    // Set up another user.
    final EppQuotaHandler otherHandler = new EppQuotaHandler(quotaManager, metrics);
    final EmbeddedChannel otherChannel = new EmbeddedChannel(otherHandler);
    final String otherClientCertHash = "hola@9x";
    otherChannel.attr(CLIENT_CERTIFICATE_HASH_KEY).set(otherClientCertHash);
    setProtocol(otherChannel);
    final DateTime later = now.plus(Duration.standardSeconds(1));

    when(quotaManager.acquireQuota(QuotaRequest.create(clientCertHash)))
        .thenReturn(QuotaResponse.create(true, clientCertHash, now));
    when(quotaManager.acquireQuota(QuotaRequest.create(otherClientCertHash)))
        .thenReturn(QuotaResponse.create(false, otherClientCertHash, later));

    // Allows the first user.
    assertThat(channel.writeInbound(message)).isTrue();
    assertThat((Object) channel.readInbound()).isEqualTo(message);
    assertThat(channel.isActive()).isTrue();

    // Blocks the second user.
    OverQuotaException e =
        assertThrows(OverQuotaException.class, () -> otherChannel.writeInbound(message));
    assertThat(e).hasMessageThat().contains(otherClientCertHash);
    verify(metrics).registerQuotaRejection("epp", otherClientCertHash);
    verifyNoMoreInteractions(metrics);
  }

  @Test
  void testSuccess_twoChannels_sameUserIds() {
    // Set up another channel for the same user.
    final EppQuotaHandler otherHandler = new EppQuotaHandler(quotaManager, metrics);
    final EmbeddedChannel otherChannel = new EmbeddedChannel(otherHandler);
    otherChannel.attr(CLIENT_CERTIFICATE_HASH_KEY).set(clientCertHash);
    setProtocol(otherChannel);
    final DateTime later = now.plus(Duration.standardSeconds(1));

    when(quotaManager.acquireQuota(QuotaRequest.create(clientCertHash)))
        .thenReturn(QuotaResponse.create(true, clientCertHash, now))
        .thenReturn(QuotaResponse.create(false, clientCertHash, later));

    // Allows the first channel.
    assertThat(channel.writeInbound(message)).isTrue();
    assertThat((Object) channel.readInbound()).isEqualTo(message);
    assertThat(channel.isActive()).isTrue();

    // Blocks the second channel.
    OverQuotaException e =
        assertThrows(OverQuotaException.class, () -> otherChannel.writeInbound(message));
    assertThat(e).hasMessageThat().contains(clientCertHash);
    verify(metrics).registerQuotaRejection("epp", clientCertHash);
    verifyNoMoreInteractions(metrics);
  }
}
