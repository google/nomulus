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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.monitoring.blackbox.ProbingAction.REMOTE_ADDRESS_KEY;
import static google.registry.monitoring.blackbox.Protocol.PROTOCOL_KEY;
import static google.registry.testing.JUnitBackports.assertThrows;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.truth.ThrowableSubject;
import google.registry.monitoring.blackbox.ProbingAction;
import google.registry.monitoring.blackbox.Protocol;
import google.registry.monitoring.blackbox.TestServers.TestServer.EchoHandler;
import google.registry.monitoring.blackbox.TestServers.WebWhoisServer;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.ReferenceCountUtil;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.junit.rules.ExternalResource;

/**
 * Helper for setting up and testing client / server connection with netty.
 *
 * <p>Code based on and almost identical to {@link google.registry.proxy.handler.NettyRule}.
 * Used in {@link SslClientInitializerTest}, {@link ProbingActionTest}, and {@link ProbingSequenceStepTest} </p>
 */
public final class NettyRule extends ExternalResource {



  // All I/O operations are done inside the single thread within this event loop group, which is
  // different from the main test thread. Therefore synchronizations are required to make sure that
  // certain I/O activities are finished when assertions are performed.
  public NettyRule() {
    eventLoopGroup = new NioEventLoopGroup(1);
  }
  public NettyRule(EventLoopGroup e) {
    eventLoopGroup = e;
  }
  private final EventLoopGroup eventLoopGroup;
  private WebWhoisServer webWhoisServer;

  // Handler attached to server's channel to record the request received.
  private EchoHandler echoHandler;

  // Handler attached to client's channel to record the response received.
  private DumpHandler dumpHandler;

  private Channel channel;

  /** Sets up a server channel bound to the given local address. */
  public void setUpServer(LocalAddress localAddress, ChannelHandler handler) {
    checkState(echoHandler == null, "Can't call setUpServer twice");
    echoHandler = new EchoHandler();
    webWhoisServer = new WebWhoisServer(eventLoopGroup, localAddress, ImmutableList.of(handler, echoHandler));
  }

  /** Sets up a client channel connecting to the give local address. */
  void setUpClient(
      LocalAddress localAddress,
      Protocol protocol,
      String host,
      ChannelHandler handler) {
    checkState(echoHandler != null, "Must call setUpServer before setUpClient");
    checkState(dumpHandler == null, "Can't call setUpClient twice");
    dumpHandler = new DumpHandler();
    ChannelInitializer<LocalChannel> clientInitializer =
        new ChannelInitializer<LocalChannel>() {
          @Override
          protected void initChannel(LocalChannel ch) throws Exception {
            // Add the given handler
            ch.pipeline().addLast(handler);
            // Add the "dumpHandler" last to log the incoming message
            ch.pipeline().addLast(dumpHandler);
          }
        };
    Bootstrap b =
        new Bootstrap()
            .group(eventLoopGroup)
            .channel(LocalChannel.class)
            .handler(clientInitializer)
            .attr(PROTOCOL_KEY, protocol)
            .attr(REMOTE_ADDRESS_KEY, host);

    channel = b.connect(localAddress).syncUninterruptibly().channel();
  }

  private void checkReady() {
    checkState(channel != null, "Must call setUpClient to finish NettyRule setup");
  }

  /** Test that custom setup to send message to current server sends right message */
  public void assertThatCustomWorks(String message) throws Exception {
    assertThat(echoHandler.getRequestFuture().get()).isEqualTo(message);

  }

  /**
   * Test that a message can go through, both inbound and outbound.
   *
   * <p>The client writes the message to the server, which echos it back and saves the string in its
   * promise. The client receives the echo and saves it in its promise. All these activities happens
   * in the I/O thread, and this call itself returns immediately.
   */
  void assertThatMessagesWork() throws Exception {
    checkReady();
    assertThat(channel.isActive()).isTrue();

    writeToChannelAndFlush(channel, "Hello, world!");
    assertThat(echoHandler.getRequestFuture().get()).isEqualTo("Hello, world!");
    assertThat(dumpHandler.getResponseFuture().get()).isEqualTo("Hello, world!");
  }

  Channel getChannel() {
    checkReady();
    return channel;
  }

  ThrowableSubject assertThatServerRootCause() {
    checkReady();
    return assertThat(
        Throwables.getRootCause(
            assertThrows(ExecutionException.class, () -> echoHandler.getRequestFuture().get())));
  }

  ThrowableSubject assertThatClientRootCause() {
    checkReady();
    return assertThat(
        Throwables.getRootCause(
            assertThrows(ExecutionException.class, () -> dumpHandler.getResponseFuture().get())));
  }



  /** A handler that dumps its inbound message to a promise that can be inspected later. */
  private static class DumpHandler extends ChannelInboundHandlerAdapter {

    private final CompletableFuture<String> responseFuture = new CompletableFuture<>();

    Future<String> getResponseFuture() {
      return responseFuture;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      // In the test we only send messages of type ByteBuf.
      assertThat(msg).isInstanceOf(ByteBuf.class);
      String response = ((ByteBuf) msg).toString(UTF_8);
      // There is no more use of this message, we should release its reference count so that it
      // can be more effectively garbage collected by Netty.
      ReferenceCountUtil.release(msg);
      // Save the string in the promise and make it as complete.
      responseFuture.complete(response);
    }

    /** Saves any inbound error into the failure cause of the promise. */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      ctx.channel().closeFuture().addListener(f -> responseFuture.completeExceptionally(cause));
    }
  }

  @Override
  protected void after() {
    Future<?> unusedFuture = eventLoopGroup.shutdownGracefully();
  }

  private static void writeToChannelAndFlush(Channel channel, String data) {
    ChannelFuture unusedFuture =
        channel.writeAndFlush(Unpooled.wrappedBuffer(data.getBytes(US_ASCII)));
  }
}

