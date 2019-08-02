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
import static google.registry.monitoring.blackbox.ProbingAction.CONNECTION_FUTURE_KEY;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import google.registry.monitoring.blackbox.TestUtils.ExistingChannelToken;
import google.registry.monitoring.blackbox.TestUtils.NewChannelToken;
import google.registry.monitoring.blackbox.exceptions.UndeterminedStateException;
import google.registry.monitoring.blackbox.handlers.ActionHandler;
import google.registry.monitoring.blackbox.handlers.ConversionHandler;
import google.registry.monitoring.blackbox.handlers.NettyRule;
import google.registry.monitoring.blackbox.handlers.TestActionHandler;
import google.registry.monitoring.blackbox.messages.TestMessage;
import google.registry.monitoring.blackbox.tokens.Token;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import javax.inject.Provider;
import org.joda.time.Duration;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

/** Unit Tests for {@link ProbingSequence}s and {@link ProbingStep}s and their specific implementations*/
public class ProbingStepTest {

  /** Basic Constants necessary for tests */
  private final static String ADDRESS_NAME = "TEST_ADDRESS";
  private final static String PROTOCOL_NAME = "TEST_PROTOCOL";
  private final static int PROTOCOL_PORT = 0;
  private final static String TEST_MESSAGE = "TEST_MESSAGE";
  private final static String SECONDARY_TEST_MESSAGE = "SECONDARY_TEST_MESSAGE";

  private final EventLoopGroup eventLoopGroup = new NioEventLoopGroup(1);
  private final Bootstrap bootstrap = new Bootstrap()
      .group(eventLoopGroup)
      .channel(LocalChannel.class);

  private final static LocalAddress address = new LocalAddress(ADDRESS_NAME);

  /** Used for testing how well probing step can create connection to blackbox server */
  @Rule
  public NettyRule nettyRule = new NettyRule(eventLoopGroup);


  /** The two main handlers we need in any test pipeline used that connects to {@link NettyRule's server}**/
  private ActionHandler testHandler = new TestActionHandler();
  private ChannelHandler conversionHandler = new ConversionHandler();



  @Test
  public void testProbingActionGenerate_embeddedChannel() throws UndeterminedStateException {
    //setup
    Protocol testProtocol = Protocol.builder()
        .setHandlerProviders(ImmutableList.of(() -> conversionHandler, () -> testHandler))
        .setName(PROTOCOL_NAME)
        .setPort(PROTOCOL_PORT)
        .setPersistentConnection(true)
        .build();

    EmbeddedChannel channel = new EmbeddedChannel(conversionHandler, testHandler);
    channel.attr(CONNECTION_FUTURE_KEY).set(channel.newSucceededFuture());

    Token testToken = new ExistingChannelToken(channel, SECONDARY_TEST_MESSAGE);

    ProbingStep testStep = ProbingStep.builder()
        .setMessageTemplate(new TestMessage(TEST_MESSAGE))
        .setBootstrap(bootstrap)
        .setDuration(Duration.ZERO)
        .setProtocol(testProtocol)
        .build();

    ProbingAction testAction = testStep.generateAction(testToken);

    assertThat(testAction.channel()).isEqualTo(channel);
    assertThat(testAction.delay()).isEqualTo(Duration.ZERO);
    assertThat(testAction.outboundMessage().toString()).isEqualTo(SECONDARY_TEST_MESSAGE);
    assertThat(testAction.host()).isEqualTo(SECONDARY_TEST_MESSAGE);
    assertThat(testAction.protocol()).isEqualTo(testProtocol);


  }

  @Test
  public void testProbingActionGenerate_newChannel() throws UndeterminedStateException {
    //setup
    Protocol testProtocol = Protocol.builder()
        .setHandlerProviders(ImmutableList.of(() -> conversionHandler, () -> testHandler))
        .setName(PROTOCOL_NAME)
        .setPort(PROTOCOL_PORT)
        .setPersistentConnection(false)
        .build();

    nettyRule.setUpServer(address);

    ProbingStep testStep = ProbingStep.builder()
        .setMessageTemplate(new TestMessage(TEST_MESSAGE))
        .setBootstrap(bootstrap)
        .setDuration(Duration.ZERO)
        .setProtocol(testProtocol)
        .build();

    // Sets up testToken to return arbitrary values, and no channel. Used when we create a new
    // channel.
    Token testToken = new NewChannelToken(ADDRESS_NAME);

    ProbingAction testAction = testStep.generateAction(testToken);

    ChannelFuture connectionFuture = testAction.channel().attr(CONNECTION_FUTURE_KEY).get();
    connectionFuture.syncUninterruptibly();

    assertThat(connectionFuture.isSuccess()).isTrue();
    assertThat(testAction.delay()).isEqualTo(Duration.ZERO);
    assertThat(testAction.outboundMessage().toString()).isEqualTo(ADDRESS_NAME);
    assertThat(testAction.host()).isEqualTo(ADDRESS_NAME);
    assertThat(testAction.protocol()).isEqualTo(testProtocol);


    }
  }
