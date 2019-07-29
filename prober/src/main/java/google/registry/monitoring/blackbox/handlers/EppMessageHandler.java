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

import com.google.common.flogger.FluentLogger;
import google.registry.monitoring.blackbox.exceptions.FailureException;
import google.registry.monitoring.blackbox.messages.EppRequestMessage;
import google.registry.monitoring.blackbox.messages.EppResponseMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import javax.inject.Inject;

/**
 * Subclass of {@link MessageHandler} that converts inbound {@link ByteBuf}
 * to custom type {@link EppResponseMessage} and similarly converts the outbound
 * {@link EppRequestMessage} to a {@link ByteBuf}
 */
public class EppMessageHandler extends ChannelDuplexHandler {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private String clTRID;
  private EppResponseMessage response;
  private EppResponseMessage greeting;
  private EppResponseMessage success;

  @Inject
  public EppMessageHandler() {
    greeting = new EppResponseMessage.Greeting();
    success = new EppResponseMessage.Success();
    response = greeting;
  }

  /** Performs conversion to {@link ByteBuf} */
  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    if (EppRequestMessage.HELLO.class.isInstance(msg)) {
      //if this is our first communication with the server, response should be expected to be a greeting
      response = greeting;
      return;
    } else {
      //otherwise we expect a success
      response = success;
    }
    //convert the outbound message to bytes and store the clTRID
    EppRequestMessage request = (EppRequestMessage) msg;
    clTRID = request.getClTRID();

    //write bytes to channel
    ctx.write(request.bytes(), promise);
  }

  /** Performs conversion from {@link ByteBuf} */
  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg)
      throws FailureException {
    try {
      //attempt to get response document from ByteBuf
      ByteBuf buf = (ByteBuf) msg;
      response.getDocument(clTRID, buf);
      logger.atInfo().log(response.toString());
    } catch(FailureException e) {

      //otherwise we log that it was unsuccessful and throw the requisite error
      logger.atInfo().withCause(e);
      throw e;
    }
    //pass response to the ActionHandler in the pipeline
    ctx.fireChannelRead(response);
  }
}
