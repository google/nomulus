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

import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.util.function.Function;
import javax.inject.Inject;

public class ActionHandler extends SimpleChannelInboundHandler<Object>
    implements Function<Object, ChannelFuture> {

  private ChannelPromise finished;
  private Channel channel;

  @Inject
  public ActionHandler() {}

  @Override
  public ChannelFuture apply(Object outboundMessage) {
    // Sends request along Outbound Handlers on the Pipeline

    channel.writeAndFlush(outboundMessage);
    return finished;
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) {
    //Once handler is added to channel pipeline, initialize channel and future for this handler
    channel = ctx.channel();
    finished = ctx.newPromise();
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, Object InboundMessage) {
    //Only purpose of Handler is to mark future as a success

    finished.setSuccess();
  }

  /**
   *Both methods are only used for testing
   */

  public Channel getChannel() {
    return channel;
  }

  public ChannelPromise getFinished() {
    return finished;
  }

}

