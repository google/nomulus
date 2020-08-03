// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.proxy.TestUtils.assertHttpResponseEquivalent;
import static google.registry.proxy.TestUtils.makeHttpGetRequest;
import static google.registry.proxy.TestUtils.makeHttpPostRequest;
import static google.registry.proxy.TestUtils.makeHttpResponse;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link WebWhoisRedirectHandler}. */
class WebWhoisRedirectHandlerTest {

  private static final String REDIRECT_HOST = "www.example.com";
  private static final String TARGET_HOST = "whois.nic.tld";

  private EmbeddedChannel channel;
  private FullHttpRequest request;
  private FullHttpResponse response;

  private void setupChannel(boolean isHttps) {
    channel = new EmbeddedChannel(new WebWhoisRedirectHandler(isHttps, REDIRECT_HOST));
  }

  private static FullHttpResponse makeRedirectResponse(
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

  // HTTP redirect tests.

  @Test
  void testSuccess_http_methodNotAllowed() {
    setupChannel(false);
    request = makeHttpPostRequest("", TARGET_HOST, "/");
    // No inbound message passed to the next handler.
    assertThat(channel.writeInbound(request)).isFalse();
    response = channel.readOutbound();
    assertHttpResponseEquivalent(
        response, makeRedirectResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, null, true, false));
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testSuccess_http_badHost() {
    setupChannel(false);
    request = makeHttpGetRequest("", "/");
    // No inbound message passed to the next handler.
    assertThat(channel.writeInbound(request)).isFalse();
    response = channel.readOutbound();
    assertHttpResponseEquivalent(
        response, makeRedirectResponse(HttpResponseStatus.BAD_REQUEST, null, true, false));
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testSuccess_http_noHost() {
    setupChannel(false);
    request = makeHttpGetRequest("", "/");
    request.headers().remove("host");
    // No inbound message passed to the next handler.
    assertThat(channel.writeInbound(request)).isFalse();
    response = channel.readOutbound();
    assertHttpResponseEquivalent(
        response, makeRedirectResponse(HttpResponseStatus.BAD_REQUEST, null, true, false));
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testSuccess_http_healthCheck() {
    setupChannel(false);
    request = makeHttpPostRequest("", TARGET_HOST, "/");
    // No inbound message passed to the next handler.
    assertThat(channel.writeInbound(request)).isFalse();
    response = channel.readOutbound();
    assertHttpResponseEquivalent(
        response, makeRedirectResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, null, true, false));
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testSuccess_http_redirectToHttps() {
    setupChannel(false);
    request = makeHttpGetRequest(TARGET_HOST, "/");
    // No inbound message passed to the next handler.
    assertThat(channel.writeInbound(request)).isFalse();
    response = channel.readOutbound();
    assertHttpResponseEquivalent(
        response,
        makeRedirectResponse(
            HttpResponseStatus.MOVED_PERMANENTLY, "https://whois.nic.tld/", true, false));
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testSuccess_http_redirectToHttps_hostAndPort() {
    setupChannel(false);
    request = makeHttpGetRequest(TARGET_HOST + ":80", "/");
    // No inbound message passed to the next handler.
    assertThat(channel.writeInbound(request)).isFalse();
    response = channel.readOutbound();
    assertHttpResponseEquivalent(
        response,
        makeRedirectResponse(
            HttpResponseStatus.MOVED_PERMANENTLY, "https://whois.nic.tld/", true, false));
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testSuccess_http_redirectToHttps_noKeepAlive() {
    setupChannel(false);
    request = makeHttpGetRequest(TARGET_HOST, "/");
    request.headers().set("connection", "close");
    // No inbound message passed to the next handler.
    assertThat(channel.writeInbound(request)).isFalse();
    response = channel.readOutbound();
    assertHttpResponseEquivalent(
        response,
        makeRedirectResponse(
            HttpResponseStatus.MOVED_PERMANENTLY, "https://whois.nic.tld/", false, false));
    assertThat(channel.isActive()).isFalse();
  }

  // HTTPS redirect tests.

  @Test
  void testSuccess_https_methodNotAllowed() {
    setupChannel(true);
    request = makeHttpPostRequest("", TARGET_HOST, "/");
    // No inbound message passed to the next handler.
    assertThat(channel.writeInbound(request)).isFalse();
    response = channel.readOutbound();
    assertHttpResponseEquivalent(
        response, makeRedirectResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, null, true, false));
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testSuccess_https_badHost() {
    setupChannel(true);
    request = makeHttpGetRequest("", "/");
    // No inbound message passed to the next handler.
    assertThat(channel.writeInbound(request)).isFalse();
    response = channel.readOutbound();
    assertHttpResponseEquivalent(
        response, makeRedirectResponse(HttpResponseStatus.BAD_REQUEST, null, true, false));
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testSuccess_https_noHost() {
    setupChannel(true);
    request = makeHttpGetRequest("", "/");
    request.headers().remove("host");
    // No inbound message passed to the next handler.
    assertThat(channel.writeInbound(request)).isFalse();
    response = channel.readOutbound();
    assertHttpResponseEquivalent(
        response, makeRedirectResponse(HttpResponseStatus.BAD_REQUEST, null, true, false));
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testSuccess_https_healthCheck() {
    setupChannel(true);
    request = makeHttpGetRequest("health-check.invalid", "/");
    // No inbound message passed to the next handler.
    assertThat(channel.writeInbound(request)).isFalse();
    response = channel.readOutbound();
    assertHttpResponseEquivalent(
        response, makeRedirectResponse(HttpResponseStatus.FORBIDDEN, null, true, false));
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testSuccess_https_redirectToDestination() {
    setupChannel(true);
    request = makeHttpGetRequest(TARGET_HOST, "/");
    // No inbound message passed to the next handler.
    assertThat(channel.writeInbound(request)).isFalse();
    response = channel.readOutbound();
    assertHttpResponseEquivalent(
        response,
        makeRedirectResponse(HttpResponseStatus.FOUND, "https://www.example.com/", true, true));
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testSuccess_https_redirectToDestination_noKeepAlive() {
    setupChannel(true);
    request = makeHttpGetRequest(TARGET_HOST, "/");
    request.headers().set("connection", "close");
    // No inbound message passed to the next handler.
    assertThat(channel.writeInbound(request)).isFalse();
    response = channel.readOutbound();
    assertHttpResponseEquivalent(
        response,
        makeRedirectResponse(HttpResponseStatus.FOUND, "https://www.example.com/", false, true));
    assertThat(channel.isActive()).isFalse();
  }
}
