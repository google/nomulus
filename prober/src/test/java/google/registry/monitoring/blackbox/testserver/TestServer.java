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

package google.registry.monitoring.blackbox.testserver;

import static google.registry.monitoring.blackbox.util.WebWhoisUtils.makeHttpResponse;
import static google.registry.monitoring.blackbox.util.WebWhoisUtils.makeRedirectResponse;

import com.google.common.collect.ImmutableList;
import google.registry.monitoring.blackbox.exception.FailureException;
import google.registry.monitoring.blackbox.message.EppMessage;
import google.registry.monitoring.blackbox.message.HttpResponseMessage;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.w3c.dom.Document;

/**
 * Mock Server Superclass whose subclasses implement specific behaviors we expect blackbox server to
 * perform
 */
public class TestServer {

  public TestServer(LocalAddress localAddress, ImmutableList<? extends ChannelHandler> handlers) {
    this(new NioEventLoopGroup(1), localAddress, handlers);
  }

  private TestServer(
      EventLoopGroup eventLoopGroup,
      LocalAddress localAddress,
      ImmutableList<? extends ChannelHandler> handlers) {
    // Creates ChannelInitializer with handlers specified
    ChannelInitializer<LocalChannel> serverInitializer =
        new ChannelInitializer<LocalChannel>() {
          @Override
          protected void initChannel(LocalChannel ch) {
            for (ChannelHandler handler : handlers) {
              ch.pipeline().addLast(handler);
            }
          }
        };
    // Sets up serverBootstrap with specified initializer, eventLoopGroup, and using
    // LocalServerChannel class
    ServerBootstrap serverBootstrap =
        new ServerBootstrap()
            .group(eventLoopGroup)
            .channel(LocalServerChannel.class)
            .childHandler(serverInitializer);

    ChannelFuture unusedFuture = serverBootstrap.bind(localAddress).syncUninterruptibly();
  }

  public static TestServer webWhoisServer(
      EventLoopGroup eventLoopGroup,
      LocalAddress localAddress,
      String redirectInput,
      String destinationInput,
      String destinationPath) {
    return new TestServer(
        eventLoopGroup,
        localAddress,
        ImmutableList.of(new RedirectHandler(redirectInput, destinationInput, destinationPath)));
  }

  public static TestServer eppServer(EventLoopGroup eventLoopGroup, LocalAddress localAddress) {
    // TODO - add LengthFieldBasedFrameDecoder to handlers in pipeline if necessary for future
    // tests.
    return new TestServer(eventLoopGroup, localAddress, ImmutableList.of(new EppHandler()));
  }

  /** Handler that will wither redirect client, give successful response, or give error messge */
  @Sharable
  static class RedirectHandler extends SimpleChannelInboundHandler<HttpRequest> {

    private String redirectInput;
    private String destinationInput;
    private String destinationPath;

    /**
     * @param redirectInput - Server will send back redirect to {@code destinationInput} when
     *     receiving a request with this host location
     * @param destinationInput - Server will send back an {@link HttpResponseStatus} OK response
     *     when receiving a request with this host location
     */
    RedirectHandler(String redirectInput, String destinationInput, String destinationPath) {
      this.redirectInput = redirectInput;
      this.destinationInput = destinationInput;
      this.destinationPath = destinationPath;
    }

    /**
     * Reads input {@link HttpRequest}, and creates appropriate {@link HttpResponseMessage} based on
     * what header location is
     */
    @Override
    public void channelRead0(ChannelHandlerContext ctx, HttpRequest request) {
      HttpResponse response;
      if (request.headers().get("host").equals(redirectInput)) {
        response =
            new HttpResponseMessage(
                makeRedirectResponse(HttpResponseStatus.MOVED_PERMANENTLY, destinationInput, true));
      } else if (request.headers().get("host").equals(destinationInput)
          && request.uri().equals(destinationPath)) {
        response = new HttpResponseMessage(makeHttpResponse(HttpResponseStatus.OK));
      } else {
        response = new HttpResponseMessage(makeHttpResponse(HttpResponseStatus.BAD_REQUEST));
      }
      ChannelFuture unusedFuture = ctx.channel().writeAndFlush(response);
    }
  }

  private static class EppHandler extends ChannelDuplexHandler {

    Document doc;
    private ChannelPromise future;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
      // TODO - pass EppMessage into future to easily read attributes of message.
      future = ctx.newPromise();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      ByteBuf buf = (ByteBuf) msg;

      byte[] messageBytes = new byte[buf.readableBytes()];
      buf.readBytes(messageBytes);

      // TODO - Break ByteBuf into multiple pieces to test how well it works with
      // LengthFieldBasedFrameDecoder.

      try {
        doc = EppMessage.byteArrayToXmlDoc(messageBytes);
        ChannelFuture unusedFuture = future.setSuccess();
      } catch (FailureException e) {
        ChannelFuture unusedFuture = future.setFailure(e);
      }
    }
  }
}
