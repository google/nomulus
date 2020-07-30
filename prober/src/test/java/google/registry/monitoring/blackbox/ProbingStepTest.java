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
import static google.registry.monitoring.blackbox.connection.ProbingAction.CONNECTION_FUTURE_KEY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import com.google.common.collect.ImmutableList;
import google.registry.monitoring.blackbox.connection.ProbingAction;
import google.registry.monitoring.blackbox.connection.Protocol;
import google.registry.monitoring.blackbox.exception.UndeterminedStateException;
import google.registry.monitoring.blackbox.handler.ActionHandler;
import google.registry.monitoring.blackbox.handler.ConversionHandler;
import google.registry.monitoring.blackbox.handler.TestActionHandler;
import google.registry.monitoring.blackbox.message.OutboundMessageType;
import google.registry.monitoring.blackbox.message.TestMessage;
import google.registry.monitoring.blackbox.token.Token;
import google.registry.networking.handler.NettyExtension;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import org.joda.time.Duration;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Unit Tests for {@link ProbingSequence}s and {@link ProbingStep}s and their specific
 * implementations
 */
public class ProbingStepTest {

  /** Basic Constants necessary for tests */
  private static final String ADDRESS_NAME = "TEST_ADDRESS";

  private static final String PROTOCOL_NAME = "TEST_PROTOCOL";
  private static final int PROTOCOL_PORT = 0;
  private static final String TEST_MESSAGE = "TEST_MESSAGE";
  private static final String SECONDARY_TEST_MESSAGE = "SECONDARY_TEST_MESSAGE";
  private static final LocalAddress address = new LocalAddress(ADDRESS_NAME);
  private final EventLoopGroup eventLoopGroup = new NioEventLoopGroup(1);
  private final Bootstrap bootstrap =
      new Bootstrap().group(eventLoopGroup).channel(LocalChannel.class);
  /** Used for testing how well probing step can create connection to blackbox server */
  @Rule public NettyExtension nettyRule = new NettyExtension();

  /**
   * The two main handlers we need in any test pipeline used that connects to {@link NettyExtension
   * 's server}
   */
  private ActionHandler testHandler = new TestActionHandler();

  private ChannelHandler conversionHandler = new ConversionHandler();

  /**
   * Creates mock {@link Token} object that returns the host and returns unchanged message when
   * modifying it.
   */
  private Token testToken(String host) throws UndeterminedStateException {
    Token token = Mockito.mock(Token.class);
    doReturn(host).when(token).host();
    doAnswer(answer -> ((OutboundMessageType) answer.getArgument(0)).modifyMessage(host))
        .when(token)
        .modifyMessage(any(OutboundMessageType.class));

    return token;
  }

  @Test
  public void testProbingActionGenerate_embeddedChannel() throws UndeterminedStateException {
    // Sets up Protocol to represent existing channel connection.
    Protocol testProtocol =
        Protocol.builder()
            .setHandlerProviders(ImmutableList.of(() -> conversionHandler, () -> testHandler))
            .setName(PROTOCOL_NAME)
            .setPort(PROTOCOL_PORT)
            .setPersistentConnection(true)
            .build();

    // Sets up an embedded channel to contain the two handlers we created already.
    EmbeddedChannel channel = new EmbeddedChannel(conversionHandler, testHandler);
    channel.attr(CONNECTION_FUTURE_KEY).set(channel.newSucceededFuture());

    // Sets up testToken to return arbitrary value, and the embedded channel. Used for when the
    // ProbingStep generates an ExistingChannelAction.
    Token testToken = testToken(SECONDARY_TEST_MESSAGE);
    doReturn(channel).when(testToken).channel();

    // Sets up generic {@link ProbingStep} that we are testing.
    ProbingStep testStep =
        ProbingStep.builder()
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
    // Sets up Protocol for when we create a new channel.
    Protocol testProtocol =
        Protocol.builder()
            .setHandlerProviders(ImmutableList.of(() -> conversionHandler, () -> testHandler))
            .setName(PROTOCOL_NAME)
            .setPort(PROTOCOL_PORT)
            .setPersistentConnection(false)
            .build();

    // Sets up generic ProbingStep that we are testing.
    ProbingStep testStep =
        ProbingStep.builder()
            .setMessageTemplate(new TestMessage(TEST_MESSAGE))
            .setBootstrap(bootstrap)
            .setDuration(Duration.ZERO)
            .setProtocol(testProtocol)
            .build();

    // Sets up testToken to return arbitrary values, and no channel. Used when we create a new
    // channel.
    Token testToken = testToken(ADDRESS_NAME);

    // Sets up server listening at LocalAddress so generated action can have successful connection.
    nettyRule.setUpServer(address);

    ProbingAction testAction = testStep.generateAction(testToken);

    ChannelFuture connectionFuture = testAction.channel().attr(CONNECTION_FUTURE_KEY).get();
    connectionFuture = connectionFuture.syncUninterruptibly();

    assertThat(connectionFuture.isSuccess()).isTrue();
    assertThat(testAction.delay()).isEqualTo(Duration.ZERO);
    assertThat(testAction.outboundMessage().toString()).isEqualTo(ADDRESS_NAME);
    assertThat(testAction.host()).isEqualTo(ADDRESS_NAME);
    assertThat(testAction.protocol()).isEqualTo(testProtocol);
  }
}
