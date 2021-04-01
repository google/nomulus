// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.proxy.handler;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.proxy.metric.FrontendMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.timeout.ReadTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.net.ssl.SSLHandshakeException;

/**
 * Handler that relays a single (framed) ByteBuf message to an HTTPS server.
 *
 * <p>This handler reads in a {@link ByteBuf}, converts it to an {@link FullHttpRequest}, and passes
 * it to the {@code channelRead} method of the next inbound handler the channel pipeline, which is
 * usually a {@code RelayHandler<FullHttpRequest>}. The relay handler writes the request to the
 * relay channel, which is connected to an HTTPS endpoint. After the relay channel receives a {@link
 * FullHttpResponse} back, its own relay handler writes the response back to this channel, which is
 * the relay channel of the relay channel. This handler then handles write request by encoding the
 * {@link FullHttpResponse} to a plain {@link ByteBuf}, and pass it down to the {@code write} method
 * of the next outbound handler in the channel pipeline, which eventually writes the response bytes
 * to the remote peer of this channel.
 *
 * <p>This handler is session aware and will store all the session cookies that the are contained in
 * the HTTP response headers, which are added back to headers of subsequent HTTP requests.
 */
public abstract class HttpsRelayServiceHandler extends ByteToMessageCodec<FullHttpResponse> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  protected static final ImmutableSet<Class<? extends Exception>> NON_FATAL_INBOUND_EXCEPTIONS =
      ImmutableSet.of(ReadTimeoutException.class, SSLHandshakeException.class);

  protected static final ImmutableSet<Class<? extends Exception>> NON_FATAL_OUTBOUND_EXCEPTIONS =
      ImmutableSet.of(NonOkHttpResponseException.class);

  private final Map<String, Cookie> cookieStore = new LinkedHashMap<>();
  private final String relayHost;
  private final String relayPath;
  private final Supplier<String> accessTokenSupplier;

  protected final FrontendMetrics metrics;

  HttpsRelayServiceHandler(
      String relayHost,
      String relayPath,
      Supplier<String> accessTokenSupplier,
      FrontendMetrics metrics) {
    this.relayHost = relayHost;
    this.relayPath = relayPath;
    this.accessTokenSupplier = accessTokenSupplier;
    this.metrics = metrics;
  }

  /**
   * Construct the {@link FullHttpRequest}.
   *
   * <p>This default method creates a bare-bone {@link FullHttpRequest} that may need to be
   * modified, e. g. adding headers specific for each protocol.
   *
   * @param byteBuf inbound message.
   */
  protected FullHttpRequest decodeFullHttpRequest(ByteBuf byteBuf) {
    FullHttpRequest request =
        new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, relayPath);
    request
        .headers()
        .set(HttpHeaderNames.USER_AGENT, "Proxy")
        .set(HttpHeaderNames.HOST, relayHost)
        .set(HttpHeaderNames.AUTHORIZATION, "Bearer " + accessTokenSupplier.get())
        .setInt(HttpHeaderNames.CONTENT_LENGTH, byteBuf.readableBytes());
    request.content().writeBytes(byteBuf);
    return request;
  }

  /**
   * Load session cookies in the cookie store and write them in to the HTTP request.
   *
   * <p>Multiple cookies are folded into one {@code Cookie} header per RFC 6265.
   *
   * @see <a href="https://tools.ietf.org/html/rfc6265#section-5.4">RFC 6265 5.4.The Cookie
   *     Header</a>
   */
  private void loadCookies(FullHttpRequest request) {
    if (!cookieStore.isEmpty()) {
      request
          .headers()
          .set(HttpHeaderNames.COOKIE, ClientCookieEncoder.STRICT.encode(cookieStore.values()));
    }
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf byteBuf, List<Object> out) {
    FullHttpRequest request = decodeFullHttpRequest(byteBuf);
    loadCookies(request);
    out.add(request);
  }

  /**
   * Construct the {@link ByteBuf}
   *
   * <p>This default method puts all the response payload into the {@link ByteBuf}.
   *
   * @param fullHttpResponse outbound http response.
   */
  ByteBuf encodeFullHttpResponse(FullHttpResponse fullHttpResponse) {
    return fullHttpResponse.content();
  }

  /**
   * Save session cookies from the HTTP response header to the cookie store.
   *
   * <p>Multiple cookies are </b>not</b> folded in to one {@code Set-Cookie} header per RFC 6265.
   *
   * @see <a href="https://tools.ietf.org/html/rfc6265#section-3">RFC 6265 3.Overview</a>
   */
  private void saveCookies(FullHttpResponse response) {
    for (String cookieString : response.headers().getAll(HttpHeaderNames.SET_COOKIE)) {
      Cookie cookie = ClientCookieDecoder.STRICT.decode(cookieString);
      cookieStore.put(cookie.name(), cookie);
    }
  }

  @Override
  protected void encode(ChannelHandlerContext ctx, FullHttpResponse response, ByteBuf byteBuf)
      throws Exception {
    if (!response.status().equals(HttpResponseStatus.OK)) {
      throw new NonOkHttpResponseException(response, ctx.channel());
    }
    saveCookies(response);
    byteBuf.writeBytes(encodeFullHttpResponse(response));
  }

  /** Terminates connection upon inbound exception. */
  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    if (NON_FATAL_INBOUND_EXCEPTIONS.contains(Throwables.getRootCause(cause).getClass())) {
      logger.atWarning().withCause(cause).log(
          "Inbound exception caught for channel %s", ctx.channel());
    } else {
      logger.atSevere().withCause(cause).log(
          "Inbound exception caught for channel %s", ctx.channel());
    }
    ChannelFuture unusedFuture = ctx.close();
  }

  /** Terminates connection upon outbound exception. */
  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    promise.addListener(
        (ChannelFuture channelFuture) -> {
          if (!channelFuture.isSuccess()) {
            Throwable cause = channelFuture.cause();
            if (NON_FATAL_OUTBOUND_EXCEPTIONS.contains(Throwables.getRootCause(cause).getClass())) {
              logger.atWarning().withCause(channelFuture.cause()).log(
                  "Outbound exception caught for channel %s", channelFuture.channel());
            } else {
              logger.atSevere().withCause(channelFuture.cause()).log(
                  "Outbound exception caught for channel %s", channelFuture.channel());
            }
            ChannelFuture unusedFuture = channelFuture.channel().close();
          }
        });
    super.write(ctx, msg, promise);
  }

  /** Exception thrown when the response status from GAE is not 200. */
  public static class NonOkHttpResponseException extends Exception {
    NonOkHttpResponseException(FullHttpResponse response, Channel channel) {
      super(
          String.format(
              "Cannot relay HTTP response status \"%s\" in channel %s:\n%s",
              response.status(), channel, response.content().toString(UTF_8)));
    }
  }
}
