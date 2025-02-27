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

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.proxy.handler.ProxyProtocolHandler.REMOTE_ADDRESS_KEY;

import google.registry.proxy.metric.FrontendMetrics;
import google.registry.util.ProxyHttpHeaders;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import java.util.function.Supplier;

/** Handler that processes WHOIS protocol logic. */
public final class WhoisServiceHandler extends HttpsRelayServiceHandler {

  private String clientAddress;

  public WhoisServiceHandler(
      String relayHost,
      String relayPath,
      boolean canary,
      Supplier<String> idTokenSupplier,
      FrontendMetrics metrics) {
    super(relayHost, relayPath, canary, idTokenSupplier, metrics);
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    metrics.registerActiveConnection("whois", "none", ctx.channel());
    super.channelActive(ctx);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    clientAddress = ctx.channel().attr(REMOTE_ADDRESS_KEY).get();
    super.channelRead(ctx, msg);
  }

  @Override
  protected FullHttpRequest decodeFullHttpRequest(ByteBuf byteBuf) {
    FullHttpRequest request = super.decodeFullHttpRequest(byteBuf);
    request
        .headers()
        .set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN)
        .set(HttpHeaderNames.ACCEPT, HttpHeaderValues.TEXT_PLAIN);
    if (clientAddress != null) {
      request
          .headers()
          .set(ProxyHttpHeaders.IP_ADDRESS, clientAddress)
          .set(ProxyHttpHeaders.FALLBACK_IP_ADDRESS, clientAddress);
    }
    return request;
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    // Close connection after a response is received, per RFC-3912
    // https://tools.ietf.org/html/rfc3912
    checkArgument(msg instanceof HttpResponse);
    promise.addListener(ChannelFutureListener.CLOSE);
    super.write(ctx, msg, promise);
  }
}
