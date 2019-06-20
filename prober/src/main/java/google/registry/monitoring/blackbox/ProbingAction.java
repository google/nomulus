// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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


import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import io.netty.bootstrap.Bootstrap;
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

  /**
   * timer that rate limits probing
   */
  private static final Timer timer = new HashedWheelTimer();

  /**
   * @return actual Duration of delay
   */
  abstract Duration delay();

  /**
   * @return message to send to server
   */
  abstract O outboundMessage();

  /**
   * @return Channel object that represents connection between prober client and server
   */
  abstract Channel channel();

  /**
   * @return The Protocol instance that represents action to be tested by this part in sequences
   */
  abstract Protocol protocol();

  /**
   * @return The requisite instance of Action Handler, which is always the last Handler in the pipeline
   */
  abstract Bootstrap bootstrap();
  abstract ActionHandler actionHandler();

  /**
   *
   * @return Builder for the ProbingAction Class
   */

  /**
   * The method that calls the ActionHandler to send a message down the channel pipeline
   * @return future that denotes when the action has been successfully performed
   */
  @Override
  public ChannelFuture call() {
    // Add the Handlers from the  run the action
    // (with delay if present), and remove the ActionHandler afterwards,
    // in case the channel is to be reused later.

    ChannelPromise finished = channel().newPromise();
    timer.newTimeout(timeout ->
            // Retry logic may also be added here.
            actionHandler().apply(outboundMessage()).addListeners(
                future -> finished.setSuccess()),
        delay().getStandardSeconds(),
        TimeUnit.SECONDS);
    return finished;
  }

  abstract static class Builder<O, B extends Builder<O, B, P>, P extends ProbingAction> {

    public abstract B delay(Duration value);

    public abstract B outboundMessage(O value);

    public abstract B protocol(Protocol value);

    public abstract B actionHandler(ActionHandler value);

    public abstract P build();
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



  /**
   * @param channelPipeline is pipeline associated with channel we want to remove handlers from
   * removes all handlers from pipeline
   */
  static void removeHandlers(ChannelPipeline channelPipeline) {
    while (channelPipeline.first() != null) {
      channelPipeline.removeFirst();
    }
  }




}


