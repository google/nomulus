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

import google.registry.monitoring.blackbox.messages.HttpRequestMessage;
import google.registry.monitoring.blackbox.messages.HttpResponseMessage;
import google.registry.monitoring.blackbox.messages.InboundMessageType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpResponse;
import javax.inject.Inject;

/**
 * Subclass of {@link MessageHandler} that converts inbound {@link FullHttpResponse}
 * to custom type {@link HttpResponseMessage} and retains {@link HttpRequestMessage}
 * in case of reuse for redirection
 */
public class WebWhoisMessageHandler extends MessageHandler {

  private HttpRequestMessage request;

  @Inject
  public WebWhoisMessageHandler() {}

  /** Retains {@link HttpRequestMessage} and calls super write method*/
  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
    request = (HttpRequestMessage) msg;
    request.retain();
    super.write(ctx, request, promise);
  }


  /** Converts {@link FullHttpResponse} to {@link HttpResponseMessage}, so it is an {@link InboundMessageType} instance */
  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    FullHttpResponse originalResponse = (FullHttpResponse) msg;
    InboundMessageType response = HttpResponseMessage.fromResponse(originalResponse);
    super.channelRead(ctx, response);
  }
}
