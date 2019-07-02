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

import static com.google.common.flogger.StackSize.SMALL;
import static google.registry.monitoring.blackbox.Protocol.PROTOCOL_KEY;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import google.registry.monitoring.blackbox.handlers.ActionHandler;
import io.netty.util.AttributeKey;
import java.util.concurrent.TimeUnit;
import org.joda.time.Duration;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import java.util.concurrent.Callable;
import javax.inject.Provider;

/**
 *Class that represents given action in sequence of probing
 *
 */


public abstract class ProbingAction<O> implements Callable<ChannelFuture> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Attribute Key that links channel to its {@link ProbingAction}*/
  public final static AttributeKey<ProbingAction> PROBING_ACTION_KEY = AttributeKey.valueOf("PROBING_ACTION_KEY");


  /** {@link ActionHandler} Associated with this {@link ProbingAction}*/
  private ActionHandler actionHandler;


  /**
   * The requisite instance of {@link ActionHandler}, which is always the last {@link ChannelHandler} in the pipeline
   */
  public ActionHandler actionHandler() {
    return actionHandler;
  }


  /** {@link Timer} that rate limits probing*/
  private static final Timer timer = new HashedWheelTimer();


  /** actual {@link Duration} of this delay*/
  public abstract Duration delay();

  /** message to send to server */
  public abstract O outboundMessage();

  /**
   * @return {@link Channel} object that represents connection between prober client and server
   */
  public abstract Channel channel();

  /**
   * @return The {@link Protocol} instance that represents action to be tested by this part in sequences
   */
  public abstract Protocol protocol();

  /**
   *
   * @return {@link Builder} that lets us build a new ProbingAction by customizing abstract methods
   */
  public abstract <O, B extends Builder<O, B, P>, P extends ProbingAction<O>> Builder<O, B, P> toBuilder();


  /**
   * The method that calls the {@link ActionHandler} to send a message down the channel pipeline
   * @return future that denotes when the action has been successfully performed
   */

  @Override
  public ChannelFuture call() {

    //Sets Action Handler to appropriately the last channel in the pipeline
    //Logs severe if the last channel in the pipeline is not
    try {
      this.actionHandler = (ActionHandler) this.channel().pipeline().last();
    } catch (ClassCastException exception) {
      logger.atSevere().withStackTrace(SMALL).log("Last Handler in the ChannelPipeline is not an ActionHandler");
    }




    ChannelPromise finished = channel().newPromise();

    //Every specified time frame by delay(), we perform the next action in our sequence
    timer.newTimeout(timeout -> {
          // Write appropriate message to pipeline
          ChannelFuture channelFuture = actionHandler().apply(outboundMessage());

          channelFuture.addListeners(
              future -> actionHandler().resetFuture(),
              future -> finished.setSuccess());
        },
        delay().getStandardSeconds(),
        TimeUnit.SECONDS);

    return finished;
  }

  public abstract static class Builder<O, B extends Builder<O, B, P>, P extends ProbingAction<O>> {

    public abstract B delay(Duration value);

    public abstract B outboundMessage(O value);

    public abstract B protocol(Protocol value);

    abstract P autoBuild();

    public P build() {
      P probingAction = autoBuild();
      probingAction.protocol().probingAction(probingAction);
      return probingAction;
    }
  }
  /**
   * @param channelPipeline is pipeline associated with channel that we want to add handlers to
   * @param handlerProviders are a list of provider objects that give us the requisite handlers Adds
   * to the pipeline, the list of handlers in the order specified
   */
  static void addHandlers(
      ChannelPipeline channelPipeline,
      ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
    for (Provider<? extends ChannelHandler> handlerProvider : handlerProviders) {
      channelPipeline.addLast(handlerProvider.get());
    }
  }

}


