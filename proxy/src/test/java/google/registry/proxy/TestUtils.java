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

/** Utility class for various helper methods used in testing. */
public class TestUtils {

  public static final String SAMPLE_CERT =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDvTCCAqWgAwIBAgIJAK/PgPT0jTwRMA0GCSqGSIb3DQEBCwUAMHUxCzAJBgNV\n"
          + "BAYTAlVTMREwDwYDVQQIDAhOZXcgWW9yazERMA8GA1UEBwwITmV3IFlvcmsxDzAN\n"
          + "BgNVBAoMBkdvb2dsZTEdMBsGA1UECwwUZG9tYWluLXJlZ2lzdHJ5LXRlc3QxEDAO\n"
          + "BgNVBAMMB2NsaWVudDEwHhcNMTUwODI2MTkxODA4WhcNNDMwMTExMTkxODA4WjB1\n"
          + "MQswCQYDVQQGEwJVUzERMA8GA1UECAwITmV3IFlvcmsxETAPBgNVBAcMCE5ldyBZ\n"
          + "b3JrMQ8wDQYDVQQKDAZHb29nbGUxHTAbBgNVBAsMFGRvbWFpbi1yZWdpc3RyeS10\n"
          + "ZXN0MRAwDgYDVQQDDAdjbGllbnQxMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIB\n"
          + "CgKCAQEAvoE/IoFJyzb0dU4NFhL8FYgy+B/GnUd5aA66CMx5xKRMbEAtIgxU8TTO\n"
          + "W+9jdTsE00Grk3Ct4KdY73CYW+6IFXL4O0K/m5S+uajh+I2UMVZJV38RAIqNxue0\n"
          + "Egv9M4haSsCVIPcX9b+6McywfYSF1bzPb2Gb2FAQO7Jb0BjlPhPMIROCrbG40qPg\n"
          + "LWrl33dz+O52kO+DyZEzHqI55xH6au77sMITsJe+X23lzQcMFUUm8moiOw0EKrj/\n"
          + "GaMTZLHP46BCRoJDAPTNx55seIwgAHbKA2VVtqrvmA2XYJQA6ipdhfKRoJFy8Z8H\n"
          + "DYsorGtazQL2HhF/5uJD25z1m5eQHQIDAQABo1AwTjAdBgNVHQ4EFgQUParEmiSR\n"
          + "U/Oqy8hr7k+MBKhZwVkwHwYDVR0jBBgwFoAUParEmiSRU/Oqy8hr7k+MBKhZwVkw\n"
          + "DAYDVR0TBAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAojsUhF6PtZrStnHBFWNR\n"
          + "ryzvANB8krZlYeX9Hkqn8zIVfAkpbVmL8aZQ7yj17jSpw47PQh3x5gwA9yc/SS0G\n"
          + "E1rGuxYH02UGbua8G0+vviSQfLtskPQzK7EIR63WNhHEo/Q9umLJkZ0LguWEBf3L\n"
          + "q8CoXv2i/RNvqVPcTNp/zCKXJZAa8wAjNRJs834AZj4k5xwyYZ3F8D5PGz+YMOmV\n"
          + "M9Qd+NdXSC/Qn7HQzFhE8p5elBV35P8oX5dXEfn0S7zOXDenp5JvvLoggOWOcKsq\n"
          + "KiWDQrsT+TMKmHL94/h4t7FghtQLMzY5SGYJsYTv/LG8tewrz6KRb/Wj3JNojyEw\n"
          + "Ug==\n"
          + "-----END CERTIFICATE-----\n";

  public static final String SAMPLE_CERT_ENCODED =
      "MIIDvTCCAqWgAwIBAgIJAK/PgPT0jTwRMA0GCSqGSIb3DQEBCwUAMHUxCzAJBgNV"
          + "BAYTAlVTMREwDwYDVQQIDAhOZXcgWW9yazERMA8GA1UEBwwITmV3IFlvcmsxDzAN"
          + "BgNVBAoMBkdvb2dsZTEdMBsGA1UECwwUZG9tYWluLXJlZ2lzdHJ5LXRlc3QxEDAO"
          + "BgNVBAMMB2NsaWVudDEwHhcNMTUwODI2MTkxODA4WhcNNDMwMTExMTkxODA4WjB1"
          + "MQswCQYDVQQGEwJVUzERMA8GA1UECAwITmV3IFlvcmsxETAPBgNVBAcMCE5ldyBZ"
          + "b3JrMQ8wDQYDVQQKDAZHb29nbGUxHTAbBgNVBAsMFGRvbWFpbi1yZWdpc3RyeS10"
          + "ZXN0MRAwDgYDVQQDDAdjbGllbnQxMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIB"
          + "CgKCAQEAvoE/IoFJyzb0dU4NFhL8FYgy+B/GnUd5aA66CMx5xKRMbEAtIgxU8TTO"
          + "W+9jdTsE00Grk3Ct4KdY73CYW+6IFXL4O0K/m5S+uajh+I2UMVZJV38RAIqNxue0"
          + "Egv9M4haSsCVIPcX9b+6McywfYSF1bzPb2Gb2FAQO7Jb0BjlPhPMIROCrbG40qPg"
          + "LWrl33dz+O52kO+DyZEzHqI55xH6au77sMITsJe+X23lzQcMFUUm8moiOw0EKrj/"
          + "GaMTZLHP46BCRoJDAPTNx55seIwgAHbKA2VVtqrvmA2XYJQA6ipdhfKRoJFy8Z8H"
          + "DYsorGtazQL2HhF/5uJD25z1m5eQHQIDAQABo1AwTjAdBgNVHQ4EFgQUParEmiSR"
          + "U/Oqy8hr7k+MBKhZwVkwHwYDVR0jBBgwFoAUParEmiSRU/Oqy8hr7k+MBKhZwVkw"
          + "DAYDVR0TBAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAojsUhF6PtZrStnHBFWNR"
          + "ryzvANB8krZlYeX9Hkqn8zIVfAkpbVmL8aZQ7yj17jSpw47PQh3x5gwA9yc/SS0G"
          + "E1rGuxYH02UGbua8G0+vviSQfLtskPQzK7EIR63WNhHEo/Q9umLJkZ0LguWEBf3L"
          + "q8CoXv2i/RNvqVPcTNp/zCKXJZAa8wAjNRJs834AZj4k5xwyYZ3F8D5PGz+YMOmV"
          + "M9Qd+NdXSC/Qn7HQzFhE8p5elBV35P8oX5dXEfn0S7zOXDenp5JvvLoggOWOcKsq"
          + "KiWDQrsT+TMKmHL94/h4t7FghtQLMzY5SGYJsYTv/LG8tewrz6KRb/Wj3JNojyEw"
          + "Ug==";

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

  public static FullHttpRequest makeEppHttpRequest(
      String content,
      String host,
      String path,
      String accessToken,
      String sslClientCertificateHash,
      String clientAddress,
      Cookie... cookies) {
    FullHttpRequest request = makeHttpPostRequest(content, host, path);
    request
        .headers()
        .set("authorization", "Bearer " + accessToken)
        .set("content-type", "application/epp+xml")
        .set("accept", "application/epp+xml")
        .set("X-SSL-Certificate", sslClientCertificateHash)
        .set("X-Forwarded-For", clientAddress);
    if (cookies.length != 0) {
      request.headers().set("cookie", ClientCookieEncoder.STRICT.encode(cookies));
    }
    return request;
  }

  public static FullHttpRequest makeEppHttpRequestWithCertificate(
      String content,
      String host,
      String path,
      String accessToken,
      String sslClientCertificateHash,
      String clientAddress,
      Cookie... cookies) {
    FullHttpRequest request =
        makeEppHttpRequest(
            content, host, path, accessToken, sslClientCertificateHash, clientAddress, cookies);
    request.headers().set("X-SSL-Full-Certificate", SAMPLE_CERT_ENCODED);
    return request;
  }

  public static FullHttpResponse makeWhoisHttpResponse(String content, HttpResponseStatus status) {
    FullHttpResponse response = makeHttpResponse(content, status);
    response.headers().set("content-type", "text/plain");
    return response;
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
