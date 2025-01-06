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

package google.registry.proxy.handler;

import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static io.netty.handler.codec.http.HttpHeaderNames.LOCATION;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import static io.netty.handler.codec.http.HttpHeaderValues.TEXT_PLAIN;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpMethod.HEAD;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.METHOD_NOT_ALLOWED;
import static io.netty.handler.codec.http.HttpResponseStatus.MOVED_PERMANENTLY;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import java.time.Duration;

/**
 * Handler that redirects web WHOIS requests to a canonical website.
 *
 * <p>ICANN requires that port 43 and web-based WHOIS are both available on whois.nic.TLD. Since we
 * expose a single IPv4/IPv6 anycast external IP address for the proxy, we need the load balancer to
 * router port 80/443 traffic to the proxy to support web WHOIS.
 *
 * <p>HTTP (port 80) traffic is simply upgraded to HTTPS (port 443) on the same host, while HTTPS
 * requests are redirected to the {@code redirectHost}, which is the canonical website that provide
 * the web WHOIS service.
 *
 * @see <a
 *     href="https://newgtlds.icann.org/sites/default/files/agreements/agreement-approved-31jul17-en.html">
 *     REGISTRY AGREEMENT</a>
 */
public class WebWhoisRedirectHandler extends SimpleChannelInboundHandler<HttpRequest> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * HTTP health check sent by GCP HTTP load balancer is set to use this host name.
   *
   * <p>Status 200 must be returned in order for a health check to be considered successful.
   *
   * @see <a
   *     href="https://cloud.google.com/load-balancing/docs/health-check-concepts#http_https_and_http2_health_checks">
   *     HTTP, HTTPS, and HTTP/2 health checks</a>
   */
  private static final String HEALTH_CHECK_HOST = "health-check.invalid";

  private static final String HSTS_HEADER_NAME = "Strict-Transport-Security";
  private static final Duration HSTS_MAX_AGE = Duration.ofDays(365);
  private static final ImmutableList<HttpMethod> ALLOWED_METHODS = ImmutableList.of(GET, HEAD);

  private final boolean isHttps;
  private final String redirectHost;

  public WebWhoisRedirectHandler(boolean isHttps, String redirectHost) {
    this.isHttps = isHttps;
    this.redirectHost = redirectHost;
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, HttpRequest msg) {
    FullHttpResponse response;
    if (!ALLOWED_METHODS.contains(msg.method())) {
      response = new DefaultFullHttpResponse(HTTP_1_1, METHOD_NOT_ALLOWED);
    } else if (Strings.isNullOrEmpty(msg.headers().get(HOST))) {
      response = new DefaultFullHttpResponse(HTTP_1_1, BAD_REQUEST);
    } else {
      // All HTTP/1.1 request must contain a Host header with the format "host:[port]".
      // See https://tools.ietf.org/html/rfc2616#section-14.23
      String host = Splitter.on(':').split(msg.headers().get(HOST)).iterator().next();
      if (host.equals(HEALTH_CHECK_HOST)) {
        // The health check request should always be sent to the HTTP port.
        response =
            isHttps
                ? new DefaultFullHttpResponse(HTTP_1_1, FORBIDDEN)
                : new DefaultFullHttpResponse(HTTP_1_1, OK);
      } else {
        // HTTP -> HTTPS is a 301 redirect, whereas HTTPS -> web WHOIS site is 302 redirect.
        response = new DefaultFullHttpResponse(HTTP_1_1, isHttps ? FOUND : MOVED_PERMANENTLY);
        String redirectUrl = String.format("https://%s/", isHttps ? redirectHost : host);
        response.headers().set(LOCATION, redirectUrl);
        // Add HSTS header to HTTPS response.
        if (isHttps) {
          response
              .headers()
              .set(HSTS_HEADER_NAME, String.format("max-age=%d", HSTS_MAX_AGE.getSeconds()));
        }
      }
    }

    // Common headers that need to be set on any response.
    response
        .headers()
        .set(CONTENT_TYPE, TEXT_PLAIN)
        .setInt(CONTENT_LENGTH, response.content().readableBytes());

    // Close the connection if keep-alive is not set in the request.
    if (!HttpUtil.isKeepAlive(msg)) {
      ChannelFuture unusedFuture =
          ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    } else {
      response.headers().set(CONNECTION, KEEP_ALIVE);
      ChannelFuture unusedFuture = ctx.writeAndFlush(response);
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    logger.atWarning().withCause(cause).log(
        (isHttps ? "HTTPS" : "HTTP") + " WHOIS inbound exception caught for channel %s",
        ctx.channel());
    ChannelFuture unusedFuture = ctx.close();
  }
}
