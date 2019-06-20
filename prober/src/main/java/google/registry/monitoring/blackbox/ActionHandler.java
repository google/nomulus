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

public class ActionHandler<O, I> extends SimpleChannelInboundHandler<I>
    implements Function<O, ChannelFuture> {

  private ChannelPromise finished;
  private Channel channel;

  @Override
  public ChannelFuture apply(O outboundMessage) {
    // Send the request to server.
    channel.writeAndFlush(outboundMessage);
    return finished;
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) {
    channel = ctx.channel();
    finished = ctx.newPromise();
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, I InboundMessage) {
    // Response received, validate it, register metrics, etc.
    // Once everything is done, mark the promise as success;

    finished.setSuccess();
  }

}

