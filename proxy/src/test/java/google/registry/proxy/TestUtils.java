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

package google.registry.proxy;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.US_ASCII;

import google.registry.util.ProxyHttpHeaders;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import java.io.IOException;

/** Utility class for various helper methods used in testing. */
public final class TestUtils {

  private TestUtils() {}

  public static FullHttpRequest makeHttpPostRequest(String content, String host, String path) {
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

  public static FullHttpRequest makeHttpPostRequest(
      String content, String host, String path, boolean canary) {
    ByteBuf buf = Unpooled.wrappedBuffer(content.getBytes(US_ASCII));
    FullHttpRequest request =
        new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, path, buf);
    request
        .headers()
        .set("user-agent", "Proxy")
        .set("host", host)
        .setInt("content-length", buf.readableBytes());
    if (canary) {
      request.headers().set("canary", "true");
    }
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

  public static FullHttpRequest makeEppHttpRequest(
      String content,
      String host,
      String path,
      boolean canary,
      String idToken,
      String sslClientCertificateHash,
      String clientAddress,
      Cookie... cookies)
      throws IOException {
    FullHttpRequest request = makeHttpPostRequest(content, host, path, canary);
    request
        .headers()
        .set("authorization", "Bearer " + idToken)
        .set("content-type", "application/epp+xml")
        .set("accept", "application/epp+xml")
        .set(ProxyHttpHeaders.CERTIFICATE_HASH, sslClientCertificateHash)
        .set(ProxyHttpHeaders.IP_ADDRESS, clientAddress)
        .set(ProxyHttpHeaders.FALLBACK_IP_ADDRESS, clientAddress);
    if (cookies.length != 0) {
      request.headers().set("cookie", ClientCookieEncoder.STRICT.encode(cookies));
    }
    return request;
  }

  public static FullHttpRequest makeEppHttpRequest(
      String content,
      String host,
      String path,
      String idToken,
      String sslClientCertificateHash,
      String clientAddress,
      Cookie... cookies)
      throws IOException {
    return makeEppHttpRequest(
        content, host, path, false, idToken, sslClientCertificateHash, clientAddress, cookies);
  }

  public static FullHttpResponse makeEppHttpResponse(
      String content, HttpResponseStatus status, Cookie... cookies) {
    FullHttpResponse response = makeHttpResponse(content, status);
    response.headers().set("content-type", "application/epp+xml");
    for (Cookie cookie : cookies) {
      response.headers().add("set-cookie", ServerCookieEncoder.STRICT.encode(cookie));
    }
    return response;
  }

  /**
   * Compares two {@link FullHttpMessage} for equivalency.
   *
   * <p>This method is needed because an HTTP message decoded and aggregated from inbound {@link
   * ByteBuf} is of a different class than the one written to the outbound {@link ByteBuf}, and The
   * {@link ByteBuf} implementations that hold the content of the HTTP messages are different, even
   * though the actual content, headers, etc. are the same.
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
