// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.monitoring.blackbox.handler;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import google.registry.monitoring.blackbox.message.InboundMessageType;
import google.registry.monitoring.blackbox.message.OutboundMessageType;
import google.registry.monitoring.blackbox.message.TestMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * {@link ChannelHandler} used in tests to convert {@link OutboundMessageType} to to {@link
 * ByteBuf}s and convert {@link ByteBuf}s to {@link InboundMessageType}
 *
 * <p>Specific type of {@link OutboundMessageType} and {@link InboundMessageType} used for
 * conversion is the {@link TestMessage} type.
 */
public class ConversionHandler extends ChannelDuplexHandler {

  /** Handles inbound conversion */
  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    ByteBuf buf = (ByteBuf) msg;
    ctx.fireChannelRead(new TestMessage(buf.toString(UTF_8)));
    buf.release();
  }

  /** Handles outbound conversion */
  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    String message = msg.toString();
    ByteBuf buf = Unpooled.wrappedBuffer(message.getBytes(US_ASCII));
    super.write(ctx, buf, promise);
  }
}
