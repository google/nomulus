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
import google.registry.monitoring.blackbox.TestUtils.TestProvider;
import google.registry.monitoring.blackbox.handlers.ActionHandler;
import google.registry.monitoring.blackbox.handlers.ConversionHandler;
import google.registry.monitoring.blackbox.handlers.NettyRule;
import google.registry.monitoring.blackbox.handlers.TestActionHandler;
import google.registry.monitoring.blackbox.messages.TestMessage;
import google.registry.monitoring.blackbox.tokens.Token;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import javax.inject.Provider;
import org.joda.time.Duration;
import org.junit.Ignore;
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

  /**
   * Sets up Mock dummy step that accurately sets next step and returns it, and when {@code accept}
   * is called, it just marks the supplied future as succeeded, returning the requisite token.
   */
  private ProbingStep dummyStep() {
    ProbingStep dummyStep = Mockito.mock(ProbingStep.class);
    doCallRealMethod().when(dummyStep).nextStep(any(ProbingStep.class));
    doCallRealMethod().when(dummyStep).nextStep();
    return dummyStep;
  }


  @Test
  public void testNewChannel() throws Exception {
    // Wrapper provider classes of handlers.
    Provider<? extends ChannelHandler> testHandlerProvider = new TestProvider<>(testHandler);
    Provider<? extends ChannelHandler> conversionHandlerProvider = new TestProvider<>(
        conversionHandler);

    // Sets up Protocol for when we create a new channel.
    Protocol testProtocol = Protocol.builder()
        .setHandlerProviders(ImmutableList.of(conversionHandlerProvider, testHandlerProvider))
        .setName(PROTOCOL_NAME)
        .setPort(PROTOCOL_PORT)
        .setPersistentConnection(false)
        .build();

    // Sets up our main step (firstStep) and throwaway step (dummyStep).
    ProbingStep firstStep = ProbingStep.builder()
        .setBootstrap(bootstrap)
        .setDuration(Duration.ZERO)
        .setMessageTemplate(new TestMessage(TEST_MESSAGE))
        .setProtocol(testProtocol)
        .build();

    //Sets up mock dummy step that returns succeeded promise when we successfully reach it.
    ProbingStep dummyStep = dummyStep();

    firstStep.nextStep(dummyStep);
    dummyStep.nextStep(firstStep);

    // Sets up testToken to return arbitrary values, and no channel. Used when we create a new
    // channel.
    Token testToken = new NewChannelToken(ADDRESS_NAME);

    //Set up blackbox server that receives our messages then echoes them back to us
    nettyRule.setUpServer(address);

    //checks that the ProbingSteps are appropriately pointing to each other
    assertThat(firstStep.nextStep()).isEqualTo(dummyStep);
    assertThat(dummyStep.nextStep()).isEqualTo(firstStep);

    //Call accept on the first step, which should send our message to the server, which will then be
    //echoed back to us, causing us to move to the next step
    firstStep.accept(testToken);

    //checks that we have appropriately sent the write message to server
    nettyRule.assertReceivedMessage(TEST_MESSAGE);

    //checks that when the future is successful, we pass down the requisite token
    verify(dummyStep, times(1)).accept(any(Token.class));
  }

  @Ignore
  @Test
  public void testWithSequence_ExistingChannel() throws Exception {
    // Wrapper provider classes of handlers.
    Provider<? extends ChannelHandler> testHandlerProvider = new TestProvider<>(testHandler);
    Provider<? extends ChannelHandler> conversionHandlerProvider = new TestProvider<>(
        conversionHandler);

    // Sets up Protocol for when a channel already exists.
    Protocol testProtocol = Protocol.builder()
        .setHandlerProviders(ImmutableList.of(conversionHandlerProvider, testHandlerProvider))
        .setName(PROTOCOL_NAME)
        .setPort(PROTOCOL_PORT)
        .setPersistentConnection(true)
        .build();

    // Sets up our main step (firstStep) and throwaway step (dummyStep).
    ProbingStep firstStep = ProbingStep.builder()
        .setBootstrap(bootstrap)
        .setDuration(Duration.ZERO)
        .setMessageTemplate(new TestMessage(TEST_MESSAGE))
        .setProtocol(testProtocol)
        .build();

    //Sets up mock dummy step that returns succeeded promise when we successfully reach it.
    ProbingStep dummyStep = dummyStep();

    firstStep.nextStep(dummyStep);
    dummyStep.nextStep(firstStep);

    // Sets up an embedded channel to contain the two handlers we created already.
    EmbeddedChannel channel = new EmbeddedChannel(conversionHandler, testHandler);

    //Assures that the channel has a succeeded connectionFuture.
    channel.attr(CONNECTION_FUTURE_KEY).set(channel.newSucceededFuture());

    // Sets up testToken to return arbitrary value, and the embedded channel. Used for when the
    // ProbingStep generates an ExistingChannelAction.
    Token testToken = new ExistingChannelToken(channel, "");

    //checks that the ProbingSteps are appropriately pointing to each other
    assertThat(firstStep.nextStep()).isEqualTo(dummyStep);
    assertThat(dummyStep.nextStep()).isEqualTo(firstStep);

    //Call accept on the first step, which should send our message through the EmbeddedChannel pipeline
    firstStep.accept(testToken);

    Object msg = channel.readOutbound();

    while (msg == null) {
      msg = channel.readOutbound();
    }
    //Ensures the accurate message is sent down the pipeline
    assertThat(((ByteBuf)channel.readOutbound()).toString(UTF_8)).isEqualTo(TEST_MESSAGE);

    //Write response to our message down EmbeddedChannel pipeline
    channel.writeInbound(Unpooled.wrappedBuffer(SECONDARY_TEST_MESSAGE.getBytes(US_ASCII)));

    //At this point, we should have received the message, so the future obtained should be marked
    // as a success
    verify(dummyStep, times(1)).accept(any(Token.class));


  }
}
