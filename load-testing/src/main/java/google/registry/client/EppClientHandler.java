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

package google.registry.client;

import static google.registry.client.EppClient.CHANNEL_NUMBER;
import static google.registry.client.EppClient.FORCE_TERMINATE;
import static google.registry.client.EppClient.INPUT_ITERATOR;
import static google.registry.client.EppClient.LOGGING_EXECUTOR;
import static google.registry.client.EppClient.LOGGING_LOCATION;
import static google.registry.client.EppClient.LOGGING_REQUEST_COMPLETE;
import static google.registry.client.EppClient.REQUEST_SENT;
import static google.registry.client.EppClient.RESPONSE_RECEIVED;
import static java.nio.file.StandardOpenOption.APPEND;

import com.google.common.flogger.FluentLogger;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Promise;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/** Handler that sends EPP requests and receives EPP responses. */
@SuppressWarnings("FutureReturnValueIgnored")
@Sharable
public class EppClientHandler extends ChannelDuplexHandler {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static class FileWriter implements Runnable {

    private final Path loggingLocation;
    private final byte[] contents;
    private final ZonedDateTime time;

    FileWriter(Path loggingLocation, byte[] contents, ZonedDateTime time) {
      this.loggingLocation = loggingLocation;
      this.contents = contents;
      this.time = time;
    }

    @Override
    public void run() {
      try {
        if (!Files.exists(loggingLocation)) {
          Files.createFile(loggingLocation);
        }
        Files.writeString(loggingLocation, time.toString() + "\n", APPEND);
        Files.write(loggingLocation, contents, APPEND);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
    ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
    ctx.channel().attr(REQUEST_SENT).get().add(now);
    ctx.channel().attr(LOGGING_REQUEST_COMPLETE).set(ctx.executor().newPromise());
    super.channelRegistered(ctx);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    Promise<Void> loggingCompletePromise = ctx.channel().attr(LOGGING_REQUEST_COMPLETE).get();
    if (!loggingCompletePromise.isDone()) {
      loggingCompletePromise.setSuccess(null);
    }
    logger.atWarning().withCause(cause).log(
        "Connection %d closed.", ctx.channel().attr(CHANNEL_NUMBER).get());
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
    Channel ch = ctx.channel();
    ctx.channel().attr(RESPONSE_RECEIVED).get().add(now);
    if (msg instanceof ByteBuf buffer) {
      byte[] contents = new byte[buffer.readableBytes()];
      buffer.readBytes(contents);
      ReferenceCountUtil.release(buffer);
      if (ch.attr(LOGGING_LOCATION).get() != null) {
        ch.attr(LOGGING_EXECUTOR)
            .get()
            .submit(new FileWriter(ch.attr(LOGGING_LOCATION).get(), contents, now));
      }
      if (ch.attr(INPUT_ITERATOR).get().hasNext()) {
        ch.writeAndFlush(ch.attr(INPUT_ITERATOR).get().next());
      } else {
        ch.attr(LOGGING_REQUEST_COMPLETE).get().setSuccess(null);
        if (ch.attr(FORCE_TERMINATE).get()) {
          ch.close();
        }
      }
    }
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
    ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
    Channel ch = ctx.channel();
    ctx.channel().attr(REQUEST_SENT).get().add(now);
    if (msg instanceof byte[] outputBytes) {
      ByteBuf buffer = Unpooled.buffer();
      buffer.writeBytes(outputBytes);
      ctx.write(buffer, promise);
      if (ch.attr(LOGGING_LOCATION).get() != null) {
        ch.attr(LOGGING_EXECUTOR)
            .get()
            .submit(new FileWriter(ch.attr(LOGGING_LOCATION).get(), outputBytes, now));
      }
    }
  }

  @Override
  public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
    Promise<Void> loggingCompletePromise = ctx.channel().attr(LOGGING_REQUEST_COMPLETE).get();
    if (!loggingCompletePromise.isDone()) {
      loggingCompletePromise.setSuccess(null);
    }
    super.close(ctx, promise);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    Promise<Void> loggingCompletePromise = ctx.channel().attr(LOGGING_REQUEST_COMPLETE).get();
    if (!loggingCompletePromise.isDone()) {
      loggingCompletePromise.setSuccess(null);
    }
    super.channelInactive(ctx);
  }
}
