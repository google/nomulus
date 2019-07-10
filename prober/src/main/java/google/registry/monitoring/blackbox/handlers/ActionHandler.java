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
import google.registry.monitoring.blackbox.messages.InboundMarker;
import google.registry.monitoring.blackbox.messages.OutboundMarker;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 *
 *
 * Abstract class that tells sends message down pipeline and
 * and tells listeners to move on when the message is received.
 */
public abstract class ActionHandler extends SimpleChannelInboundHandler<InboundMarker> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  protected ChannelPromise finished;
  protected OutboundMarker outboundMessage;

  /** Writes and flushes specified outboundMessage to channel pipeline and returns future
   * that is marked as success when ActionHandler next reads from the channel */
  public ChannelFuture getFuture(OutboundMarker outboundMessage) {
    //Action Handlers subclasses require the outboundMessage for additional logic
    this.outboundMessage = outboundMessage;

    //returns the ChannelPromise initialized
    return finished;
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) {
    //Once handler is added to channel pipeline, initialize channel and future for this handler
    finished = ctx.newPromise();
  }

  @Override
  public void channelRead0(ChannelHandlerContext ctx, InboundMarker inboundMessage) throws Exception {
    //simply marks finished as success
    finished.setSuccess();
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    logger.atSevere().withCause(cause).log(String.format(
        "Attempted Action was unsuccessful with channel: %s, having pipeline: %s",
        ctx.channel().toString(),
        ctx.channel().pipeline().toString()));

    finished.setFailure(cause);
    ChannelFuture closedFuture = ctx.channel().close();
    closedFuture.addListener(f -> logger.atInfo().log("Unsuccessful channel connection closed"));
  }
}

