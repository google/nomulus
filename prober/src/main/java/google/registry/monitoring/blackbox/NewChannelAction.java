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

import static google.registry.monitoring.blackbox.Protocol.PROTOCOL_KEY;

import com.google.auto.value.AutoValue;
import com.google.common.flogger.FluentLogger;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;

/**
 *
 * @param <O> Generic Type of Outbound Message
 * @param <C> For testing Purposes to use different kinds of channels (other than NioSocketChannel)
 * Subclass of ProbingAction where each instance creates a new channel
 */
@AutoValue
public abstract class NewChannelAction<O, C extends AbstractChannel> extends ProbingAction<O> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**{@link Channel} created from bootstrap connection to protocol's specified host and port*/
  private Channel channel;

  /**
   *
   * @return {@link Bootstrap} object associated with this {@link ProbingAction}
   */
  abstract Bootstrap bootstrap();

  /**
   * @return {@link Channel} Object instantiated in call
   */
  @Override
  public Channel channel() {
    return this.channel;
  }


  @Override
  public abstract Builder<O, C> toBuilder();

  /**
   * Creates channel from bootstrap and protocol given to instance
   * @return ChannelFuture instance that is set to success when previous action has
   * finished and requisite time as passed
   */
  @Override
  public ChannelFuture call() {

    //Calls on bootstrap method
    Bootstrap bootstrap = bootstrap();
    bootstrap.handler(
        new ChannelInitializer<C>() {
          @Override
          protected void initChannel(C outboundChannel)
              throws Exception {
            //Uses Handlers from Protocol to fill pipeline
            addHandlers(outboundChannel.pipeline(), protocol().handlerProviders());
          }
        })
        .attr(PROTOCOL_KEY, protocol());


    logger.atInfo().log("Initialized bootstrap with channel Handlers");
    //ChannelFuture that performs action when connection is established

    ChannelFuture connectionFuture;

    if (protocol().host() != null) {
      connectionFuture = bootstrap.connect(protocol().host(), protocol().port());
    } else {
      connectionFuture = bootstrap.connect(protocol().address());
    }

    //ChannelPromise that we return
    ChannelPromise finished = connectionFuture.channel().newPromise();

    //set current channel to one associated with connectionFuture
    this.channel = connectionFuture.channel();

    //When connection is established call super.call and set returned listener to success
    connectionFuture.addListener(
        (ChannelFuture channelFuture) -> {
          if (channelFuture.isSuccess()) {
            logger.atInfo().log(String.format("Successful connection to remote host: %s at port: %d", protocol().host(), protocol().port()));
            ChannelFuture future = super.call();
            future.addListener(f -> finished.setSuccess());

          } else {
            //if we receive a failure, log the failure, and close the channel
            logger.atSevere().withCause(channelFuture.cause()).log(
                "Cannot connect to relay channel for %s channel: %s.",
                protocol().name(), this.channel());
            ChannelFuture unusedFuture = channel().close();
          }
        }
    );
    return finished;
  }

  public static <O, C extends AbstractChannel> NewChannelAction.Builder<O, C> builder() {
    return new AutoValue_NewChannelAction.Builder<>();
  }


  @AutoValue.Builder
  public static abstract class Builder<O, C extends AbstractChannel> extends ProbingAction.Builder<O, Builder<O, C>, NewChannelAction<O, C>> {
    //specifies bootstrap in this builder
    public abstract NewChannelAction.Builder<O, C> bootstrap(Bootstrap value);

  }

}
