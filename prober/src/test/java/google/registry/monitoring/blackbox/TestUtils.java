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

package google.registry.monitoring.blackbox;

import static java.nio.charset.StandardCharsets.US_ASCII;

import google.registry.monitoring.blackbox.messages.OutboundMessageType;
import google.registry.monitoring.blackbox.tokens.Token;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

/** Utility class for various helper methods used in testing. */
public class TestUtils {

  public static FullHttpRequest makeHttpGetRequest(String host, String path) {
    FullHttpRequest request =
        new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path);
    request.headers().set("host", host).setInt("content-length", 0);
    return request;
  }

  public static FullHttpResponse makeHttpResponse(String content, HttpResponseStatus status) {
    ByteBuf buf = Unpooled.wrappedBuffer(content.getBytes(US_ASCII));
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf);
    response.headers().setInt("content-length", buf.readableBytes());
    return response;
  }

  public static FullHttpResponse makeHttpResponse(HttpResponseStatus status) {
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
    response.headers().setInt("content-length", 0);
    return response;
  }

  /** Creates HttpResponse given status, redirection location, and other necessary inputs */
  public static FullHttpResponse makeRedirectResponse(
      HttpResponseStatus status, String location, boolean keepAlive, boolean hsts) {
    FullHttpResponse response = makeHttpResponse("", status);
    response.headers().set("content-type", "text/plain");
    if (location != null) {
      response.headers().set("location", location);
    }
    if (keepAlive) {
      response.headers().set("connection", "keep-alive");
    }
    return response;
  }

  /** Basic outline for {@link Token} instances to be used in tests */
  static abstract class TestToken extends Token {
    protected String host;

    protected TestToken(String host) {
      this.host = host;
    }
    @Override
    public Token next() {
      return this;
    }

    @Override
    public OutboundMessageType modifyMessage(OutboundMessageType message) {
      return message;
    }

    @Override
    public String host() {
      return host;
    }

  }

  /** {@link TestToken} instance that creates new channel */
  public static class NewChannelToken extends TestToken {
    public NewChannelToken(String host) {
      super(host);
    }
    @Override
    public Channel channel() {
      return null;
    }
  }

  /** {@link TestToken} instance that passes in existing channel */
  public static class ExistingChannelToken extends TestToken {
    private Channel channel;

    public ExistingChannelToken(Channel channel, String host) {
      super(host);
      this.channel = channel;
    }
    @Override
    public Channel channel() {
      return channel;
    }
  }
}

