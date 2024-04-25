// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.client;

import static java.nio.charset.StandardCharsets.US_ASCII;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.net.Inet4Address;
import java.net.InetSocketAddress;

/**
 * Handler that adds the proxy protocol header at the beginning of a connection.
 *
 * <p>This handler removes itself after the header is written.
 */
@SuppressWarnings("FutureReturnValueIgnored")
public class ProxyHeaderHandler extends ChannelInboundHandlerAdapter {
  private static final String PROXY_HEADER_TEMPLATE = "PROXY TCP%d %s %s %d %d\r\n";

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
    InetSocketAddress localAddress = (InetSocketAddress) ctx.channel().localAddress();
    int ipVersion = remoteAddress.getAddress() instanceof Inet4Address ? 4 : 6;
    String proxyHeader =
        String.format(
            PROXY_HEADER_TEMPLATE,
            ipVersion,
            localAddress.getAddress().getHostAddress(),
            remoteAddress.getAddress().getHostAddress(),
            localAddress.getPort(),
            remoteAddress.getPort());
    ctx.writeAndFlush(Unpooled.wrappedBuffer(proxyHeader.getBytes(US_ASCII)))
        .addListener(
            (ChannelFuture cf) -> {
              if (cf.isSuccess()) {
                cf.channel().pipeline().remove(this);
                System.out.println("Removing " + this + "...");
                ctx.fireChannelActive();
              } else {
                System.out.println("Cannot add proxy header, closing connection...");
                cf.channel().close();
              }
            });
  }
}
