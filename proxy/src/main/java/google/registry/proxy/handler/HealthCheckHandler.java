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

package google.registry.proxy.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.nio.charset.StandardCharsets;

/** A handler that responds to GCP load balancer health check message */
public class HealthCheckHandler extends ChannelInboundHandlerAdapter {

  private final ByteBuf checkRequest;
  private final ByteBuf checkResponse;

  public HealthCheckHandler(String checkRequest, String checkResponse) {
    this.checkRequest = Unpooled.wrappedBuffer(checkRequest.getBytes(StandardCharsets.US_ASCII));
    this.checkResponse = Unpooled.wrappedBuffer(checkResponse.getBytes(StandardCharsets.US_ASCII));
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    ByteBuf buf = (ByteBuf) msg;
    if (buf.equals(checkRequest)) {
      ChannelFuture unusedFuture = ctx.writeAndFlush(checkResponse);
    }
    buf.release();
  }
}
