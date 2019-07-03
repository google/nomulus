package google.registry.monitoring.blackbox;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import google.registry.monitoring.blackbox.handlers.ActionHandler;
import google.registry.monitoring.blackbox.handlers.NettyRule;
import google.registry.monitoring.blackbox.handlers.TestActionHandler;
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
/** Unit tests for {@link NewChannelAction}.
 * Attempts to test how well WebWhoIsActionHandler works
 * when responding to all possible types of responses
 * */
@RunWith(JUnit4.class)
public class ProbingActionTest {
  private final String TEST_MESSAGE = "MESSAGE_TEST";
  private final EventLoopGroup eventLoopGroup = new NioEventLoopGroup(1);
  private final LocalAddress address = new LocalAddress("TEST_ADDRESS");

  private Bootstrap bootstrap = new Bootstrap()
    .group(eventLoopGroup)
    .channel(LocalChannel.class);


  private ActionHandler<Object, Object> testHandler = new TestActionHandler();
  private Provider<? extends ChannelHandler> provider = new TestProvider<>(testHandler);

  private ProbingAction<Object> newChannelAction;
  private ProbingAction<Object> existingChannelAction;
  private EmbeddedChannel channel;
  private Protocol protocol = Protocol.builder()
      .handlerProviders(ImmutableList.of(provider))
      .name("TEST_PROTOCOL")
      .port(0)
      .build()
      .address(address);

  @Rule
  public NettyRule nettyRule = new NettyRule(eventLoopGroup);

  private void setupNewChannelAction() {
    newChannelAction = NewChannelAction.<Object, LocalChannel>builder()
        .bootstrap(bootstrap)
        .protocol(protocol)
        .delay(Duration.ZERO)
        .outboundMessage(Unpooled.wrappedBuffer(TEST_MESSAGE.getBytes(US_ASCII)))
        .build();
  }

  private void setupChannel() {
    channel = new EmbeddedChannel();
  }

  private void setupExistingChannelAction(Channel channel) {
    existingChannelAction = ExistingChannelAction.builder()
        .channel(channel)
        .protocol(protocol)
        .delay(Duration.ZERO)
        .outboundMessage(Unpooled.wrappedBuffer(TEST_MESSAGE.getBytes(US_ASCII)))
        .build();
  }

  @Test
  public void testBehavior_existingChannel() {
    //setup
    setupChannel();
    setupExistingChannelAction(channel);
    channel.pipeline().addLast(testHandler);

    //makes sure that when setting everything up, we have the right pointers between Protocols
    //and ProbingActions
    assertThat(existingChannelAction.protocol().probingAction()).isEqualTo(existingChannelAction);

    ChannelFuture future = existingChannelAction.call();

    //Ensures that we pass in the right message to the channel and haven't marked the future as success yet
    Object msg = channel.readOutbound();
    assertThat(msg).isInstanceOf(ByteBuf.class);
    String response = ((ByteBuf) msg).toString(UTF_8);
    assertThat(response).isEqualTo(TEST_MESSAGE);
    assertThat(future.isSuccess()).isFalse();

    //after writing inbound, we should have a success
    channel.writeInbound("Should Succeed");
    assertThat(future.isSuccess()).isTrue();

    //checks that we reset the same actionHandler's future
    ChannelFuture secondFuture = existingChannelAction.call();
    assertThat(secondFuture.isSuccess()).isFalse();


  }

  @Test
  public void testSuccess_newChannel() throws Exception{
    //setup
    setupNewChannelAction();
    nettyRule.setUpServer(address, new ChannelInboundHandlerAdapter());

    ChannelFuture future = newChannelAction.call();

    //Tests to see if message is properly sent to remote server
    nettyRule.assertThatCustomWorks(TEST_MESSAGE);

    future.sync();
    //Tests to see that, since server responds, we have set future to true
    assertThat(future.isSuccess());


  }



}
