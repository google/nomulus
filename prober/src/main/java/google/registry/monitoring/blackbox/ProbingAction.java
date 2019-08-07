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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.flogger.StackSize.SMALL;
import static google.registry.monitoring.blackbox.Protocol.PROTOCOL_KEY;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import google.registry.monitoring.blackbox.exceptions.UndeterminedStateException;
import google.registry.monitoring.blackbox.handlers.ActionHandler;
import google.registry.monitoring.blackbox.messages.OutboundMessageType;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.local.LocalAddress;
import io.netty.util.AttributeKey;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.inject.Provider;
import org.joda.time.Duration;

/**
 * AutoValue class that represents action generated by {@link ProbingStep}
 *
 * <p> Inherits from {@link Callable<ChannelFuture>}, as it has can be called
 * to perform its specified task, and return the {@link ChannelFuture} that will be informed when
 * the task has been completed </p>
 *
 * <p> Is an immutable class, as it is comprised of the tools necessary for making a specific type
 * of connection. It goes hand in hand with {@link Protocol}, which specifies the kind of overall
 * connection to be made. {@link Protocol} gives the outline and {@link ProbingAction} gives the
 * details of that connection.</p>
 *
 * <p> In its build, if there is no channel supplied, it will create a channel from the attributes
 * already supplied. Then, it only sends the {@link OutboundMessageType} down the pipeline when
 * informed that the connection is successful. If the channel is supplied, the connection future is
 * automatically set to successful.</p>
 */

@AutoValue
public abstract class ProbingAction implements Callable<ChannelFuture> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * {@link AttributeKey} in channel that gives {@link ChannelFuture} that is set to success when
   * channel is active.
   */
  public static final AttributeKey<ChannelFuture> CONNECTION_FUTURE_KEY = AttributeKey
      .valueOf("CONNECTION_FUTURE_KEY");

  /**
   * {@link AttributeKey} in channel that gives the information of the channel's host.
   */
  public static final AttributeKey<String> REMOTE_ADDRESS_KEY = AttributeKey
      .valueOf("REMOTE_ADDRESS_KEY");

  /**
   * {@link Timer} that rate limits probing
   */
  private static final Timer timer = new HashedWheelTimer();

  /**
   * Actual {@link Duration} of this delay
   */
  public abstract Duration delay();

  /**
   * {@link OutboundMessageType} instance that we write and flush down pipeline to server
   */
  public abstract OutboundMessageType outboundMessage();

  /**
   * {@link Channel} object that either created by or passed into this {@link ProbingAction}
   * instance
   */
  @Nullable
  public abstract Channel channel();

  /**
   * The {@link Protocol} instance that specifies type of connection
   */
  public abstract Protocol protocol();

  /**
   * The hostname of the remote host we have a connection or will make a connection to
   */
  public abstract String host();

  /**
   * Performs the work of the actual action
   *
   * <p>First, checks if channel is active by setting a listener to perform the bulk of the work
   * when the connection future is successful.</p>
   *
   * <p>Once the connection is successful, we establish which of the handlers in the pipeline is
   * the {@link ActionHandler}.From that, we can obtain a future that is marked as a success when
   * we receive an expected response from the server.</p>
   *
   * <p>Next, we set a timer set to a specified delay. After the delay has passed, we send the
   * {@code outboundMessage} down the channel pipeline, and when we observe a success or failure,
   * we inform the {@link ProbingStep} of this.</p>
   *
   * @return {@link ChannelFuture} that denotes when the action has been successfully performed.
   */


  /**
   * Method that calls on {@code performAction} when it is certain channel connection is
   * established.
   */
  @Override
  public ChannelFuture call() {
    //ChannelPromise that we return
    ChannelPromise finished = channel().newPromise();

    //Ensures channel has been set up with connection future as an attribute
    checkNotNull(channel().attr(CONNECTION_FUTURE_KEY).get());

    //When connection is established call super.call and set returned listener to success
    channel().attr(CONNECTION_FUTURE_KEY).get().addListener(
        (ChannelFuture connectionFuture) -> {
          if (connectionFuture.isSuccess()) {
            logger.atInfo().log(String
                .format("Successful connection to remote host: %s at port: %d", host(),
                    protocol().port()));

            ActionHandler actionHandler;
            try {
              actionHandler = channel().pipeline().get(ActionHandler.class);
            } catch (ClassCastException e) {
              //If we don't actually have an ActionHandler instance, we have an issue, and throw
              // an UndeterminedStateException
              logger.atSevere().withStackTrace(SMALL).log("ActionHandler not in Channel Pipeline");
              throw new UndeterminedStateException("No Action Handler found in pipeline");
            }
            ChannelFuture channelFuture = actionHandler.getFinishedFuture();

            timer.newTimeout(timeout -> {
                  // Write appropriate outboundMessage to pipeline
                  ChannelFuture unusedFutureWriteAndFlush =
                      channel().writeAndFlush(outboundMessage());
                  channelFuture.addListeners(
                      future -> {
                        if (future.isSuccess()) {
                          ChannelFuture unusedFuture = finished.setSuccess();
                        } else {
                          ChannelFuture unusedFuture = finished.setFailure(future.cause());
                        }
                      },
                      //If we don't have a persistent connection, close the connection to this
                      // channel
                      future -> {
                        if (!protocol().persistentConnection()) {

                          ChannelFuture closedFuture = channel().close();
                          closedFuture.addListener(
                              f -> {
                                if (f.isSuccess()) {
                                  logger.atInfo()
                                      .log("Closed stale channel. Moving on to next ProbingStep");
                                } else {
                                  logger.atWarning()
                                      .log(
                                          "Could not close channel. Stale connection still exists"
                                              + ".");
                                }
                              }
                          );
                        }
                      }
                  );
                },
                delay().getStandardSeconds(),
                TimeUnit.SECONDS);
          } else {
            //if we receive a failure, log the failure, and close the channel
            logger.atSevere().withCause(connectionFuture.cause()).log(
                "Cannot connect to relay channel for %s channel: %s.",
                protocol().name(), this.channel());
            ChannelFuture unusedFuture = channel().close();
          }
        }
    );
    return finished;
  }


  /**
   * {@link AutoValue.Builder} that does work of creating connection when not already present.
   */
  @AutoValue.Builder
  public abstract static class Builder {

    private Bootstrap bootstrap;

    public Builder setBootstrap(Bootstrap bootstrap) {
      this.bootstrap = bootstrap;
      return this;
    }

    public abstract Builder setDelay(Duration value);

    public abstract Builder setOutboundMessage(OutboundMessageType value);

    public abstract Builder setProtocol(Protocol value);

    public abstract Builder setHost(String value);

    public abstract Builder setChannel(Channel channel);

    abstract Protocol protocol();

    abstract Channel channel();

    abstract String host();

    abstract ProbingAction autoBuild();

    public ProbingAction build() {
      SocketAddress address;
      try {
        InetAddress hostAddress = InetAddress.getByName(host());
        address = new InetSocketAddress(hostAddress, protocol().port());
      } catch (UnknownHostException e) {
        address = new LocalAddress(host());
      }

      checkArgument(channel() == null ^ bootstrap == null,
          "One and only one of bootstrap and channel must be supplied.");
      //If a channel is supplied, nothing is needed to be done

      //Otherwise, a Bootstrap must be supplied and be used for creating the channel
      if (channel() == null) {
        bootstrap.handler(
            new ChannelInitializer<Channel>() {
              @Override
              protected void initChannel(Channel outboundChannel)
                  throws Exception {
                //Uses Handlers from Protocol to fill pipeline
                addHandlers(outboundChannel.pipeline(), protocol().handlerProviders());
              }
            })
            .attr(PROTOCOL_KEY, protocol())
            .attr(REMOTE_ADDRESS_KEY, host());

        logger.atInfo().log("Initialized bootstrap with channel Handlers");
        //ChannelFuture that performs action when connection is established
        ChannelFuture connectionFuture = bootstrap.connect(address);

        setChannel(connectionFuture.channel());
        connectionFuture.channel().attr(CONNECTION_FUTURE_KEY).set(connectionFuture);
      }

      //now we can actually build the ProbingAction
      return autoBuild();
    }
  }

  public static Builder builder() {
    return new AutoValue_ProbingAction.Builder();
  }

  /**
   * Adds provided {@link ChannelHandler}s to the {@link ChannelPipeline} specified
   *
   * @param channelPipeline is pipeline associated with channel that we want to add handlers to
   * @param handlerProviders are a list of provider objects that give us the requisite handlers Adds
   * to the pipeline, the list of handlers in the order specified
   */
  private static void addHandlers(
      ChannelPipeline channelPipeline,
      ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
    for (Provider<? extends ChannelHandler> handlerProvider : handlerProviders) {
      channelPipeline.addLast(handlerProvider.get());
    }
  }


  @Override
  public final String toString() {
    return String.format(
        "ProbingAction with delay: %d\n"
            + "outboundMessage: %s\n"
            + "protocol: %s\n"
            + "host: %s\n",
        delay().getStandardSeconds(),
        outboundMessage(),
        protocol(),
        host()
    );
  }
}
