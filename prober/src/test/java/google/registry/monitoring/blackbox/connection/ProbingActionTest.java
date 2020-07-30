// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.monitoring.blackbox.connection;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.monitoring.blackbox.connection.ProbingAction.CONNECTION_FUTURE_KEY;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import google.registry.monitoring.blackbox.handler.ActionHandler;
import google.registry.monitoring.blackbox.handler.ConversionHandler;
import google.registry.monitoring.blackbox.handler.TestActionHandler;
import google.registry.monitoring.blackbox.message.TestMessage;
import google.registry.networking.handler.NettyExtension;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import org.joda.time.Duration;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Unit tests for {@link ProbingAction} subtypes
 *
 * <p>Attempts to test how well each {@link ProbingAction} works with an {@link ActionHandler}
 * subtype when receiving to all possible types of responses
 */
class ProbingActionTest {

  private static final String TEST_MESSAGE = "MESSAGE_TEST";
  private static final String SECONDARY_TEST_MESSAGE = "SECONDARY_MESSAGE_TEST";
  private static final String PROTOCOL_NAME = "TEST_PROTOCOL";
  private static final String ADDRESS_NAME = "TEST_ADDRESS";
  private static final int TEST_PORT = 0;

  @RegisterExtension NettyExtension nettyExtension = new NettyExtension();

  /**
   * We use custom Test {@link ActionHandler} and {@link ConversionHandler} so test depends only on
   * {@link ProbingAction}
   */
  private ActionHandler testHandler = new TestActionHandler();

  private ChannelHandler conversionHandler = new ConversionHandler();

  // TODO - Currently, this test fails to receive outbound messages from the embedded channel, which
  // we will fix in a later release.
  @Disabled
  @Test
  void testSuccess_existingChannel() {
    // setup
    EmbeddedChannel channel = new EmbeddedChannel(conversionHandler, testHandler);
    channel.attr(CONNECTION_FUTURE_KEY).set(channel.newSucceededFuture());

    // Sets up a Protocol corresponding to when a connection exists.
    Protocol protocol =
        Protocol.builder()
            .setHandlerProviders(ImmutableList.of(() -> conversionHandler, () -> testHandler))
            .setName(PROTOCOL_NAME)
            .setPort(TEST_PORT)
            .setPersistentConnection(true)
            .build();

    // Sets up a  ProbingAction that creates a channel using test specified attributes.
    ProbingAction action =
        ProbingAction.builder()
            .setChannel(channel)
            .setProtocol(protocol)
            .setDelay(Duration.ZERO)
            .setOutboundMessage(new TestMessage(TEST_MESSAGE))
            .setHost("")
            .build();

    // tests main function of ProbingAction
    ChannelFuture future = action.call();

    // Obtains the outboundMessage passed through pipeline after delay
    Object msg = null;
    while (msg == null) {
      msg = channel.readOutbound();
    }
    // tests the passed message is exactly what we expect
    assertThat(msg).isInstanceOf(ByteBuf.class);
    String request = ((ByteBuf) msg).toString(UTF_8);
    assertThat(request).isEqualTo(TEST_MESSAGE);

    // Ensures that we haven't marked future as done until response is received.
    assertThat(future.isDone()).isFalse();

    // After writing inbound, we should have a success as we use an EmbeddedChannel, ensuring all
    // operations happen on the main thread - i.e. synchronously.
    channel.writeInbound(Unpooled.wrappedBuffer(SECONDARY_TEST_MESSAGE.getBytes(US_ASCII)));
    assertThat(future.isSuccess()).isTrue();

    assertThat(testHandler.toString()).isEqualTo(SECONDARY_TEST_MESSAGE);
  }

  @Test
  void testSuccess_newChannel() throws Exception {
    // setup

    LocalAddress address = new LocalAddress(ADDRESS_NAME);
    Bootstrap bootstrap =
        new Bootstrap().group(nettyExtension.getEventLoopGroup()).channel(LocalChannel.class);

    // Sets up a Protocol corresponding to when a new connection is created.
    Protocol protocol =
        Protocol.builder()
            .setHandlerProviders(ImmutableList.of(() -> conversionHandler, () -> testHandler))
            .setName(PROTOCOL_NAME)
            .setPort(TEST_PORT)
            .setPersistentConnection(false)
            .build();

    nettyExtension.setUpServer(address);

    // Sets up a ProbingAction with existing channel using test specified attributes.
    ProbingAction action =
        ProbingAction.builder()
            .setBootstrap(bootstrap)
            .setProtocol(protocol)
            .setDelay(Duration.ZERO)
            .setOutboundMessage(new TestMessage(TEST_MESSAGE))
            .setHost(ADDRESS_NAME)
            .build();

    // tests main function of ProbingAction
    ChannelFuture future = action.call();

    // Tests to see if message is properly sent to remote server
    nettyExtension.assertReceivedMessage(TEST_MESSAGE);

    future = future.syncUninterruptibly();
    // Tests to see that, since server responds, we have set future to true
    assertThat(future.isSuccess()).isTrue();
    assertThat(((TestActionHandler) testHandler).getResponse().toString()).isEqualTo(TEST_MESSAGE);
  }
}
