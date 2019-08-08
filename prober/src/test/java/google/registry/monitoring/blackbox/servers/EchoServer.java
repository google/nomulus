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

package google.registry.monitoring.blackbox.servers;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.JUnitBackports.assertThrows;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.truth.ThrowableSubject;
import google.registry.monitoring.blackbox.connection.Protocol;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.util.ReferenceCountUtil;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Helper for setting up and testing client / server connection with netty.
 *
 * <p>Code based on and almost identical to {@link google.registry.proxy.handler.NettyRule}.
 * Used in {@link SslClientInitializerTest}, {@link ProbingActionTest}, and {@link
 * ProbingSequenceStepTest} </p>
 */
public final class EchoServer extends TestServer {


  // Handler attached to server's channel to record the request received.
  private EchoHandler echoHandler;
  // Handler attached to client's channel to record the success received.
  private DumpHandler dumpHandler;
  private int port;

  // All I/O operations are done inside the single thread within this event loop group, which is
  // different from the main test thread. Therefore synchronizations are required to make sure that
  // certain I/O activities are finished when assertions are performed.
  public EchoServer() {
    super();
  }

  public EchoServer(EventLoopGroup eventLoopGroup) {
    super(eventLoopGroup);
  }

  private static void writeToChannelAndFlush(Channel channel, String data) {
    ChannelFuture unusedFuture =
        channel.writeAndFlush(Unpooled.wrappedBuffer(data.getBytes(US_ASCII)));
  }

  /**
   * Sets up a server channel bound to the given port
   */
  public void setUpServer(LocalAddress address, ChannelHandler handler) {
    checkState(echoHandler == null, "Can't call setUpServer twice");
    echoHandler = new EchoHandler();
    super.setupServer(address, ImmutableList.of(handler, echoHandler));

  }

  public void setUpClient(LocalAddress address, Protocol protocol, String host,
      ChannelHandler handler) {
    checkState(echoHandler != null, "Must call setUpServer before setUpClient");
    checkState(dumpHandler == null, "Can't call setUpClient twice");
    dumpHandler = new DumpHandler();
    super.setUpClient(address, protocol, host, ImmutableList.of(handler, dumpHandler));
  }

  public ThrowableSubject assertThatServerRootCause() {
    checkReady();
    return assertThat(
        Throwables.getRootCause(
            assertThrows(ExecutionException.class, () -> echoHandler.getRequestFuture().get())));
  }

  public ThrowableSubject assertThatClientRootCause() {
    checkReady();
    return assertThat(
        Throwables.getRootCause(
            assertThrows(ExecutionException.class, () -> dumpHandler.getResponseFuture().get())));
  }

  /**
   * Test that a message can go through, both inbound and outbound.
   *
   * <p>The client writes the message to the server, which echos it back and saves the string in
   * its promise. The client receives the echo and saves it in its promise. All these activities
   * happens in the I/O thread, and this call itself returns immediately.
   */
  public void assertThatMessagesWork() throws Exception {
    checkReady();
    assertThat(channel.isActive()).isTrue();

    writeToChannelAndFlush(channel, "Hello, world!");
    assertThat(echoHandler.getRequestFuture().get()).isEqualTo("Hello, world!");
    assertThat(dumpHandler.getResponseFuture().get()).isEqualTo("Hello, world!");
  }

  /**
   * Test that custom setup to send message to current server sends right message
   */
  public void assertThatCustomWorks(String message) throws Exception {
    assertThat(echoHandler.getRequestFuture().get()).isEqualTo(message);

  }

  @Override
  protected void after() {
    Future<?> unusedFuture = eventLoopGroup.shutdownGracefully();
  }

  /**
   * A handler that echoes back its inbound message. The message is also saved in a promise for
   * inspection later.
   */
  public static class EchoHandler extends ChannelInboundHandlerAdapter {

    private final CompletableFuture<String> requestFuture = new CompletableFuture<>();

    public Future<String> getRequestFuture() {
      return requestFuture;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      // In the test we only send messages of type ByteBuf.

      assertThat(msg).isInstanceOf(ByteBuf.class);
      String request = ((ByteBuf) msg).toString(UTF_8);
      // After the message is written back to the client, fulfill the promise.
      ChannelFuture unusedFuture =
          ctx.writeAndFlush(msg).addListener(f -> requestFuture.complete(request));
    }

    /**
     * Saves any inbound error as the cause of the promise failure.
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      ChannelFuture unusedFuture =
          ctx.channel().closeFuture().addListener(f -> requestFuture.completeExceptionally(cause));
    }
  }

  /**
   * A handler that dumps its inbound message to a promise that can be inspected later.
   */
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

    /**
     * Saves any inbound error into the failure cause of the promise.
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      ctx.channel().closeFuture().addListener(f -> responseFuture.completeExceptionally(cause));
    }
  }
}


