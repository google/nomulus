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
import static google.registry.proxy.handler.RelayHandler.RELAY_BUFFER_KEY;
import static google.registry.proxy.handler.RelayHandler.RELAY_CHANNEL_KEY;

import com.google.common.collect.ImmutableList;
import google.registry.proxy.Protocol;
import google.registry.proxy.Protocol.BackendProtocol;
import google.registry.proxy.Protocol.FrontendProtocol;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayDeque;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RelayHandler}. */
class RelayHandlerTest {

  private static final class ExpectedType {}

  private static final class OtherType {}

  private final RelayHandler<ExpectedType> relayHandler = new RelayHandler<>(ExpectedType.class);
  private final EmbeddedChannel inboundChannel = new EmbeddedChannel(relayHandler);
  private final EmbeddedChannel outboundChannel = new EmbeddedChannel();
  private final FrontendProtocol frontendProtocol =
      Protocol.frontendBuilder()
          .port(0)
          .name("FRONTEND")
          .handlerProviders(ImmutableList.of())
          .relayProtocol(
              Protocol.backendBuilder()
                  .host("host.invalid")
                  .port(0)
                  .name("BACKEND")
                  .handlerProviders(ImmutableList.of())
                  .build())
          .build();
  private final BackendProtocol backendProtocol = frontendProtocol.relayProtocol();

  @BeforeEach
  void beforeEach() {
    inboundChannel.attr(RELAY_CHANNEL_KEY).set(outboundChannel);
    inboundChannel.attr(RELAY_BUFFER_KEY).set(new ArrayDeque<>());
    inboundChannel.attr(PROTOCOL_KEY).set(frontendProtocol);
    outboundChannel.attr(PROTOCOL_KEY).set(backendProtocol);
  }

  @Test
  void testSuccess_relayInboundMessageOfExpectedType() {
    ExpectedType inboundMessage = new ExpectedType();
    // Relay handler intercepted the message, no further inbound message.
    assertThat(inboundChannel.writeInbound(inboundMessage)).isFalse();
    // Message wrote to outbound channel as-is.
    ExpectedType relayedMessage = outboundChannel.readOutbound();
    assertThat(relayedMessage).isEqualTo(inboundMessage);
  }

  @Test
  void testSuccess_ignoreInboundMessageOfOtherType() {
    OtherType inboundMessage = new OtherType();
    // Relay handler ignores inbound message of other types, the inbound message is passed along.
    assertThat(inboundChannel.writeInbound(inboundMessage)).isTrue();
    // Nothing is written into the outbound channel.
    ExpectedType relayedMessage = outboundChannel.readOutbound();
    assertThat(relayedMessage).isNull();
  }

  @Test
  void testSuccess_frontClosed() {
    inboundChannel.attr(RELAY_BUFFER_KEY).set(null);
    inboundChannel.attr(PROTOCOL_KEY).set(backendProtocol);
    outboundChannel.attr(PROTOCOL_KEY).set(frontendProtocol);
    ExpectedType inboundMessage = new ExpectedType();
    // Outbound channel (frontend) is closed.
    outboundChannel.finish();
    assertThat(inboundChannel.writeInbound(inboundMessage)).isFalse();
    ExpectedType relayedMessage = outboundChannel.readOutbound();
    assertThat(relayedMessage).isNull();
    // Inbound channel (backend) should stay open.
    assertThat(inboundChannel.isActive()).isTrue();
    assertThat(inboundChannel.attr(RELAY_BUFFER_KEY).get()).isNull();
  }

  @Test
  void testSuccess_backendClosed_enqueueBuffer() {
    ExpectedType inboundMessage = new ExpectedType();
    // Outbound channel (backend) is closed.
    outboundChannel.finish();
    assertThat(inboundChannel.writeInbound(inboundMessage)).isFalse();
    ExpectedType relayedMessage = outboundChannel.readOutbound();
    assertThat(relayedMessage).isNull();
    // Inbound channel (frontend) should stay open.
    assertThat(inboundChannel.isActive()).isTrue();
    assertThat(inboundChannel.attr(RELAY_BUFFER_KEY).get()).containsExactly(inboundMessage);
  }

  @Test
  void testSuccess_channelRead_relayNotSet() {
    ExpectedType inboundMessage = new ExpectedType();
    inboundChannel.attr(RELAY_CHANNEL_KEY).set(null);
    // Nothing to read.
    assertThat(inboundChannel.writeInbound(inboundMessage)).isFalse();
    // Inbound channel is closed.
    assertThat(inboundChannel.isActive()).isFalse();
  }
}
