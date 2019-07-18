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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.US_ASCII;

import google.registry.monitoring.blackbox.Tokens.Token;
import google.registry.monitoring.blackbox.messages.InboundMessageType;
import google.registry.monitoring.blackbox.messages.OutboundMessageType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.DefaultPromise;
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
  }

  /** {@link ProbingStep} subclass that performs probing Steps functions, without time delay */
  public static class TestStep extends ProbingStep<LocalChannel> {

    public TestStep(Protocol protocol, String testMessage, LocalAddress address) {
      super(protocol, new DuplexMessageTest(testMessage));
      this.address = address;
      this.duration = Duration.ZERO;
    }
  }

  /** {@link ProbingStep} subclass that is solely used to note when the previous {@link ProbingStep} has completed its action */
  public static class DummyStep extends ProbingStep<LocalChannel> {
    private DefaultPromise<Token> future;

    public DummyStep(Protocol protocol, EventLoopGroup eventLoopGroup) {
      super(protocol, new DuplexMessageTest());
      future = new DefaultPromise<Token>(eventLoopGroup.next()) {};
      duration = Duration.ZERO;
    }

    @Override
    public void accept(Token token) {
      future.setSuccess(token);
    }
    public DefaultPromise<Token> getFuture() {
      return future;
    }
  }

  /** Basic outline for {@link Token} instances to be used in tests */
  private static abstract class TestToken extends Token {
    private String host;

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
    public String getHost() {
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

  /**
   * Compares two {@link FullHttpMessage} for equivalency.
   *
   * <p>This method is needed because an HTTP message decoded and aggregated from inbound {@link
   * ByteBuf} is of a different class than the one written to the outbound {@link ByteBuf}, and The
   * {@link ByteBuf} implementations that hold the content of the HTTP messages are different, even
   * though the actual content, headers, etc are the same.
   *
   * <p>This method is not type-safe, msg1 & msg2 can be a request and a response, respectively. Do
   * not use this method directly.
   */
  private static void assertHttpMessageEquivalent(HttpMessage msg1, HttpMessage msg2) {
    assertThat(msg1.protocolVersion()).isEqualTo(msg2.protocolVersion());
    assertThat(msg1.headers()).isEqualTo(msg2.headers());
    if (msg1 instanceof FullHttpRequest && msg2 instanceof FullHttpRequest) {
      assertThat(((FullHttpRequest) msg1).content()).isEqualTo(((FullHttpRequest) msg2).content());
    }
  }

  public static void assertHttpResponseEquivalent(FullHttpResponse res1, FullHttpResponse res2) {
    assertThat(res1.status()).isEqualTo(res2.status());
    assertHttpMessageEquivalent(res1, res2);
  }

  public static void assertHttpRequestEquivalent(HttpRequest req1, HttpRequest req2) {
    assertHttpMessageEquivalent(req1, req2);
  }
}

