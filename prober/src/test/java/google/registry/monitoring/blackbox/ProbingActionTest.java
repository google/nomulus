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

package google.registry.monitoring.blackbox;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import google.registry.monitoring.blackbox.connection.ProbingAction;
import google.registry.monitoring.blackbox.connection.Protocol;
import google.registry.monitoring.blackbox.handlers.ActionHandler;
import google.registry.monitoring.blackbox.handlers.ConversionHandler;
import google.registry.monitoring.blackbox.handlers.TestActionHandler;
import google.registry.monitoring.blackbox.messages.TestMessage;
import google.registry.monitoring.blackbox.testservers.EchoServer;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import javax.inject.Provider;
import org.joda.time.Duration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
/**
 * Unit tests for {@link ProbingAction} subtypes
 *
 * <p>Attempts to test how well each {@link ProbingAction} works with an {@link ActionHandler}
 * subtype when receiving to all possible types of responses</p>
 * */
@RunWith(JUnit4.class)
public class ProbingActionTest {
  /** Necessary Constants for test */
  private final String TEST_MESSAGE = "MESSAGE_TEST";
  private final String SECONDARY_TEST_MESSAGE = "SECONDARY_MESSAGE_TEST";
  private final String PROTOCOL_NAME = "TEST_PROTOCOL";
  private final String ADDRESS_NAME = "TEST_ADDRESS";
  private final int TEST_PORT = 0;

  private final EventLoopGroup eventLoopGroup = new NioEventLoopGroup(1);
  private final LocalAddress address = new LocalAddress(ADDRESS_NAME);
  private Bootstrap bootstrap = new Bootstrap()
    .group(eventLoopGroup)
    .channel(LocalChannel.class);

  /** We use custom Test {@link ActionHandler} and {@link ConversionHandler} so test depends only on {@link ProbingAction} */
  private ActionHandler testHandler = new TestActionHandler();
  private ChannelHandler conversionHandler = new ConversionHandler();

  private Provider<? extends ChannelHandler> testHandlerProvider = () -> testHandler;
  private Provider<? extends ChannelHandler> conversionHandlerProvider = () -> conversionHandler;

  private ProbingAction newChannelAction;
  private ProbingAction existingChannelAction;
  private EmbeddedChannel channel;
  private Protocol protocol;

  /** Used for testing how well probing step can create connection to blackbox server */
  @Rule
  public EchoServer echoServer = new EchoServer(eventLoopGroup);

  /** Sets up a {@link Protocol} corresponding to when a new connection is created */
  private void setupNewChannelProtocol() {
    protocol = Protocol.builder()
        .setHandlerProviders(ImmutableList.of(conversionHandlerProvider, testHandlerProvider))
        .setName(PROTOCOL_NAME)
        .setPort(TEST_PORT)
        .setPersistentConnection(false)
        .build();
  }
  /** Sets up a {@link Protocol} corresponding to when a new connection exists */
  private void setupExistingChannelProtocol() {
    protocol = Protocol.builder()
        .setHandlerProviders(ImmutableList.of(conversionHandlerProvider, testHandlerProvider))
        .setName(PROTOCOL_NAME)
        .setPort(TEST_PORT)
        .setPersistentConnection(true)
        .build();
  }

  /** Sets up a {@link ProbingAction} that creates a channel using test specified attributes. */
  private void setupNewChannelAction() {
    newChannelAction = ProbingAction.builder()
        .setBootstrap(bootstrap)
        .setProtocol(protocol)
        .setDelay(Duration.ZERO)
        .setOutboundMessage(new TestMessage(TEST_MESSAGE))
        .setHost("")
        .setAddress(address)
        .build();
  }

  private void setupChannel() {
    channel = new EmbeddedChannel();
  }

  /** Sets up a {@link ProbingAction} with existing channel using test specified attributes. */
  private void setupExistingChannelAction(Channel channel) {
    existingChannelAction = ProbingAction.builder()
        .setChannel(channel)
        .setProtocol(protocol)
        .setDelay(Duration.ZERO)
        .setOutboundMessage(new DuplexMessageTest(TEST_MESSAGE))
        .setHost("")
        .build();
  }

  @Test
  public void testBehavior_existingChannel() throws InternalException {
    //setup
    setupChannel();
    setupExistingChannelProtocol();
    channel.pipeline().addLast(conversionHandler);
    channel.pipeline().addLast(testHandler);
    setupExistingChannelAction(channel);


    ChannelFuture future = existingChannelAction.call();

    //Ensures that we pass in the right message to the channel and haven't marked the future as success yet
    Object msg = channel.readOutbound();
    assertThat(msg).isInstanceOf(ByteBuf.class);
    String response = ((ByteBuf) msg).toString(UTF_8);
    assertThat(response).isEqualTo(TEST_MESSAGE);
    assertThat(future.isSuccess()).isFalse();

    //after writing inbound, we should have a success
    channel.writeInbound(Unpooled.wrappedBuffer(SECONDARY_TEST_MESSAGE.getBytes(US_ASCII)));
    future.syncUninterruptibly();
    assertThat(future.isSuccess()).isTrue();

    assertThat(testHandler.toString()).isEqualTo(SECONDARY_TEST_MESSAGE);
  }

  @Test
  public void testSuccess_newChannel() throws Exception {
    //setup
    setupNewChannelProtocol();

    nettyRule.setUpServer(address, new ChannelInboundHandlerAdapter());
    setupNewChannelAction();
    ChannelFuture future = newChannelAction.call();

    //Tests to see if message is properly sent to remote server
    nettyRule.assertThatCustomWorks(TEST_MESSAGE);

    future.sync();
    //Tests to see that, since server responds, we have set future to true
    assertThat(future.isSuccess());
    assertThat(testHandler.toString()).isEqualTo(TEST_MESSAGE);
  }
}

