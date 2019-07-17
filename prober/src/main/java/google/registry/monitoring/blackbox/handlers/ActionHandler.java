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

package google.registry.monitoring.blackbox.handlers;

import com.google.common.flogger.FluentLogger;
import com.google.common.flogger.StackSize;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpResponse;
import java.util.function.Function;
import javax.inject.Inject;

/**
 *
 * @param <I> Generic Type of Inbound Message
 * @param <O> Generic Type of Outbound Message
 * Abstract class that tells sends message down pipeline and
 * and tells listeners to move on when the message is received.
 */
public abstract class ActionHandler<I, O> extends SimpleChannelInboundHandler<I>
    implements Function<O, ChannelFuture> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  protected ChannelPromise finished;
  private Channel channel;


  /** returns ChannelPromise for when inbound message is recieved
   * a
   * @param outboundMessage
   * @return
   */
  @Override
  public ChannelFuture apply(O outboundMessage) {
    // Sends request along Outbound Handlers on the Pipeline

    channel.writeAndFlush(outboundMessage);
    return finished;

  }

  public void resetFuture() {
    finished = channel.newPromise();
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) {
    //Once handler is added to channel pipeline, initialize channel and future for this handler

    channel = ctx.channel();
    finished = ctx.newPromise();
  }

  @Override
  public void channelRead0(ChannelHandlerContext ctx, I inboundMessage) throws Exception{
    //Only purpose of Handler is to mark future as a success

    finished.setSuccess();
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    logger.atSevere().withCause(cause).withStackTrace(StackSize.FULL).log("Exception Caught");
  }
}
