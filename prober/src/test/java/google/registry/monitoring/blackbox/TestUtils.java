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

import google.registry.monitoring.blackbox.exceptions.UndeterminedStateException;
import google.registry.monitoring.blackbox.messages.InboundMessageType;
import google.registry.monitoring.blackbox.messages.OutboundMessageType;
import google.registry.monitoring.blackbox.tokens.Token;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.DefaultPromise;
import java.net.SocketAddress;
import javax.inject.Provider;
import org.joda.time.Duration;

/** Utility class for various helper methods used in testing. */
public class TestUtils {

  static FullHttpRequest makeHttpPostRequest(String content, String host, String path) {
    ByteBuf buf = Unpooled.wrappedBuffer(content.getBytes(US_ASCII));
    FullHttpRequest request =
        new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, path, buf);
    request
        .headers()
        .set("user-agent", "Proxy")
        .set("host", host)
        .setInt("content-length", buf.readableBytes());
    return request;
  }

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
    response.headers().set("content-type", "text/plain").set("content-length", "0");
    if (location != null) {
      response.headers().set("location", location);
    }
    if (keepAlive) {
      response.headers().set("connection", "keep-alive");
    }
    if (hsts) {
      response.headers().set("Strict-Transport-Security", "max-age=31536000");
    }
    return response;
  }

  public static FullHttpRequest makeWhoisHttpRequest(
      String content, String host, String path, String accessToken) {
    FullHttpRequest request = makeHttpPostRequest(content, host, path);
    request
        .headers()
        .set("authorization", "Bearer " + accessToken)
        .set("content-type", "text/plain")
        .set("accept", "text/plain");
    return request;
  }

  public static FullHttpResponse makeWhoisHttpResponse(String content, HttpResponseStatus status) {
    FullHttpResponse response = makeHttpResponse(content, status);
    response.headers().set("content-type", "text/plain");
    return response;
  }

  /** {@link Provider} test subtype for the purpose of easily adding requisite {@link ChannelHandler}s to pipeline */
  public static class TestProvider<E> implements Provider<E> {

    private E obj;

    public TestProvider(E obj) {
      this.obj = obj;
    }

    @Override
    public E get() {
      return obj;
    }
  }

  /** {@link InboundMessageType} and {@link OutboundMessageType} type for the purpose of containing String messages to be passed down channel */
  public static class DuplexMessageTest implements OutboundMessageType, InboundMessageType {

    String message;

    public DuplexMessageTest() {
      message = "";
    }

    public DuplexMessageTest(String msg) {
      message = msg;
    }

    @Override
    public String toString() {
      return message;
    }

    @Override
    public OutboundMessageType modifyMessage(String... args) throws UndeterminedStateException {
      message = args[0];
      return this;
    }
  }

  /** {@link ProbingStep} subclass that performs probing Steps functions, without time delay */
  public static ProbingStep testStep(Protocol protocol, String testMessage, Bootstrap bootstrap, SocketAddress address) {
    return ProbingStep.builder()
        .setProtocol(protocol)
        .setDuration(Duration.ZERO)
        .setMessageTemplate(new DuplexMessageTest(testMessage))
        .setBootstrap(bootstrap)
        .build();

  }
  public static ProbingStep dummyStep(EventLoopGroup eventLoopGroup) {
    return new DummyStep(eventLoopGroup);
  }

  /** {@link ProbingStep} subclass that is solely used to note when the previous {@link ProbingStep} has completed its action */
  public static class DummyStep extends ProbingStep {
    private DefaultPromise<Token> future;

    public DummyStep(EventLoopGroup eventLoopGroup) {
      future = new DefaultPromise<Token>(eventLoopGroup.next()) {
      };
    }

    @Override
    Duration duration() {
      return null;
    }

    @Override
    Protocol protocol() {
      return null;
    }

    @Override
    OutboundMessageType messageTemplate() {
      return null;
    }

    @Override
    Bootstrap bootstrap() {
      return null;
    }

    @Override
    public void accept(Token token) {
      future.setSuccess(token);
    }
    public DefaultPromise<Token> getFuture() {
      return future;
    }

    @Override
    public String toString() {
      return "Dummy Step";
    }
  }

  /** Basic outline for {@link Token} instances to be used in tests */
  private static abstract class TestToken extends Token {
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

  /** {@link TestToken} instance that creates new channel */
  public static class ProbingSequenceTestToken extends TestToken {
    public ProbingSequenceTestToken() {
      super("");
    }
    @Override
    public Channel channel() {
      return null;
    }

    public void addToHost(String suffix) {
      host += suffix;
    }

  }
}

