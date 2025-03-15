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

package google.registry.monitoring.blackbox.handler;

import com.google.common.flogger.FluentLogger;
import google.registry.monitoring.blackbox.exception.FailureException;
import google.registry.monitoring.blackbox.message.EppRequestMessage;
import google.registry.monitoring.blackbox.message.EppResponseMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * {@link io.netty.channel.ChannelHandler} that converts inbound {@link ByteBuf} to custom type
 * {@link EppResponseMessage} and similarly converts the outbound {@link EppRequestMessage} to a
 * {@link ByteBuf}. Always comes right before {@link EppActionHandler} in channel pipeline.
 */
public class EppMessageHandler extends ChannelDuplexHandler {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * Corresponding {@link EppResponseMessage} that we expect to receive back from server.
   *
   * <p>We always expect the first response to be an {@link EppResponseMessage.Greeting}.
   */
  private EppResponseMessage response;

  @Inject
  public EppMessageHandler(@Named("greeting") EppResponseMessage greetingResponse) {
    response = greetingResponse;
  }

  /** Performs conversion to {@link ByteBuf}. */
  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {

    // If the message is Hello, don't actually pass it on, just wait for server greeting.
    // otherwise, we store what we expect a successful response to be
    EppRequestMessage request = (EppRequestMessage) msg;
    response = request.getExpectedResponse();

    if (!response.name().equals("greeting")) {
      // then we write the ByteBuf representation of the EPP message to the server
      ChannelFuture unusedFuture = ctx.write(request.bytes(), promise);
    }
  }

  /** Performs conversion from {@link ByteBuf} */
  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws FailureException {
    try {
      // attempt to get response document from ByteBuf
      ByteBuf buf = (ByteBuf) msg;
      response.getDocument(buf);
      logger.atInfo().log(response.toString());
    } catch (FailureException e) {

      // otherwise we log that it was unsuccessful and throw the requisite error
      logger.atInfo().withCause(e).log("Failure in current step.");
      throw e;
    }
    // pass response to the ActionHandler in the pipeline
    ctx.fireChannelRead(response);
  }
}
