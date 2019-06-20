package google.registry.monitoring.blackbox;

import com.google.auto.value.AutoValue;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundInvoker;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.joda.time.Duration;

@AutoValue
public abstract class NewChannelAction<O> extends ProbingAction<O> {
  abstract Bootstrap bootstrap();

  private Channel channel;

  @Override
  public Channel channel() {
    return this.channel;
  }

  @Override
  public ChannelFuture call() {
    Bootstrap bootstrap = bootstrap();
    bootstrap.handler(
        new ChannelInitializer<NioSocketChannel>() {
          @Override
          protected void initChannel(NioSocketChannel outboundChannel)
              throws Exception {
            addHandlers(
                outboundChannel.pipeline(), protocol().handlerProviders());
          }
        });

    ChannelFuture connectionFuture = bootstrap.connect(protocol().host(), protocol().port());
    ChannelPromise finished = null;

    connectionFuture.addListener(
        (ChannelFuture channelFuture) -> {
          if (channelFuture.isSuccess()) {
            this.channel = channelFuture.channel();
            ChannelFuture future = super.call();
            future.addListener(f -> finished.setSuccess());

          } else {

          }
        }
    );
    return finished;
  }

  public static ProbingAction.Builder builder() {
    return new AutoValue_NewChannelAction.Builder();
  }


  @AutoValue.Builder
  public static abstract class Builder<O> extends ProbingAction.Builder<O, Builder<O>, NewChannelAction<O>> {

    public abstract NewChannelAction.Builder<O> bootstrap(Bootstrap value);

    public abstract NewChannelAction.Builder<O> outboundMessage(O value);

  }

}
