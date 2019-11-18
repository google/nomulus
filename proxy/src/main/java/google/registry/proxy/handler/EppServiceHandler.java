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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.networking.handler.SslServerInitializer.CLIENT_CERTIFICATE_PROMISE_KEY;
import static google.registry.proxy.handler.ProxyProtocolHandler.REMOTE_ADDRESS_KEY;
import static google.registry.util.X509Utils.getCertificateHash;

import com.google.common.flogger.FluentLogger;
import google.registry.proxy.metric.FrontendMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Promise;
import java.security.cert.X509Certificate;
import java.util.function.Supplier;

/** Handler that processes EPP protocol logic. */
public class EppServiceHandler extends HttpsRelayServiceHandler {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * Attribute key to the client certificate hash whose value is set when the certificate promise is
   * fulfilled.
   */
  public static final AttributeKey<String> CLIENT_CERTIFICATE_HASH_KEY =
      AttributeKey.valueOf("CLIENT_CERTIFICATE_HASH_KEY");

  /** Name of the HTTP header that stores the client certificate hash. */
  public static final String SSL_CLIENT_CERTIFICATE_HASH_FIELD = "X-SSL-Certificate";

  /** Name of the HTTP header that stores the client IP address. */
  public static final String FORWARDED_FOR_FIELD = "X-Forwarded-For";

  /** Name of the HTTP header that indicates if the EPP session should be closed. */
  public static final String EPP_SESSION_FIELD = "Epp-Session";

  public static final String EPP_CONTENT_TYPE = "application/epp+xml";

  private final byte[] helloBytes;

  private String sslClientCertificateHash;
  private String clientAddress;

  public EppServiceHandler(
      String relayHost,
      String relayPath,
      Supplier<String> accessTokenSupplier,
      byte[] helloBytes,
      FrontendMetrics metrics) {
    super(relayHost, relayPath, accessTokenSupplier, metrics);
    this.helloBytes = helloBytes;
  }

  /**
   * Write <hello> to the server after SSL handshake completion to request <greeting>
   *
   * <p>When handling EPP over TCP, the server should issue a <greeting> to the client when a
   * connection is established. Nomulus app however does not automatically sends the <greeting> upon
   * connection. The proxy therefore first sends a <hello> to registry to request a <greeting>
   * response.
   *
   * <p>The <hello> request is only sent after SSL handshake is completed between the client and the
   * proxy so that the client certificate hash is available, which is needed to communicate with the
   * server. Because {@link SslHandshakeCompletionEvent} is triggered before any calls to {@link
   * #channelRead} are scheduled by the event loop executor, the <hello> request is guaranteed to be
   * the first message sent to the server.
   *
   * @see <a href="https://tools.ietf.org/html/rfc5734">RFC 5732 EPP Transport over TCP</a>
   * @see <a href="https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt">The Proxy
   *     Protocol</a>
   */
  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    Promise<X509Certificate> unusedPromise =
        ctx.channel()
            .attr(CLIENT_CERTIFICATE_PROMISE_KEY)
            .get()
            .addListener(
                (Promise<X509Certificate> promise) -> {
                  if (promise.isSuccess()) {
                    sslClientCertificateHash = getCertificateHash(promise.get());
                    // Set the client cert hash key attribute for both this channel,
                    // used for collecting metrics on specific clients.
                    ctx.channel().attr(CLIENT_CERTIFICATE_HASH_KEY).set(sslClientCertificateHash);
                    clientAddress = ctx.channel().attr(REMOTE_ADDRESS_KEY).get();
                    metrics.registerActiveConnection(
                        "epp", sslClientCertificateHash, ctx.channel());
                    channelRead(ctx, Unpooled.wrappedBuffer(helloBytes));
                  } else {
                    logger.atWarning().withCause(promise.cause()).log(
                        "Cannot finish handshake for channel %s, remote IP %s",
                        ctx.channel(), ctx.channel().attr(REMOTE_ADDRESS_KEY).get());
                    ChannelFuture unusedFuture = ctx.close();
                  }
                });
    super.channelActive(ctx);
  }

  @Override
  protected FullHttpRequest decodeFullHttpRequest(ByteBuf byteBuf) {
    checkNotNull(clientAddress, "Cannot obtain client address.");
    checkNotNull(sslClientCertificateHash, "Cannot obtain client certificate hash.");
    FullHttpRequest request = super.decodeFullHttpRequest(byteBuf);
    request
        .headers()
        .set(SSL_CLIENT_CERTIFICATE_HASH_FIELD, sslClientCertificateHash)
        .set(FORWARDED_FOR_FIELD, clientAddress)
        .set(HttpHeaderNames.CONTENT_TYPE, EPP_CONTENT_TYPE)
        .set(HttpHeaderNames.ACCEPT, EPP_CONTENT_TYPE);
    return request;
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    checkArgument(msg instanceof HttpResponse);
    HttpResponse response = (HttpResponse) msg;
    String sessionAliveValue = response.headers().get(EPP_SESSION_FIELD);
    if (sessionAliveValue != null && sessionAliveValue.equals("close")) {
      promise.addListener(ChannelFutureListener.CLOSE);
    }
    super.write(ctx, msg, promise);
  }
}
