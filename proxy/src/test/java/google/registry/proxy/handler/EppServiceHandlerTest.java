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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.networking.handler.SslServerInitializer.CLIENT_CERTIFICATE_PROMISE_KEY;
import static google.registry.proxy.TestUtils.assertHttpRequestEquivalent;
import static google.registry.proxy.TestUtils.makeEppHttpResponse;
import static google.registry.proxy.handler.ProxyProtocolHandler.REMOTE_ADDRESS_KEY;
import static google.registry.testing.JUnitBackports.assertThrows;
import static google.registry.util.X509Utils.getCertificateHash;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.base.Throwables;
import google.registry.proxy.TestUtils;
import google.registry.proxy.handler.HttpsRelayServiceHandler.NonOkHttpResponseException;
import google.registry.proxy.metric.FrontendMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.concurrent.Promise;
import java.security.cert.X509Certificate;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link EppServiceHandler}. */
@RunWith(JUnit4.class)
public class EppServiceHandlerTest {

  private static final String HELLO =
      "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
          + "<epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\">\n"
          + "  <hello/>\n"
          + "</epp>\n";

  private static final String RELAY_HOST = "registry.example.tld";
  private static final String RELAY_PATH = "/epp";
  private static final String ACCESS_TOKEN = "this.access.token";
  private static final String CLIENT_ADDRESS = "epp.client.tld";
  private static final String PROTOCOL = "epp";

  private X509Certificate clientCertificate;

  private final FrontendMetrics metrics = mock(FrontendMetrics.class);

  private final EppServiceHandler eppServiceHandler =
      new EppServiceHandler(
          RELAY_HOST,
          RELAY_PATH,
          () -> ACCESS_TOKEN,
          HELLO.getBytes(UTF_8),
          metrics);

  private EmbeddedChannel channel;

  private void setHandshakeSuccess(EmbeddedChannel channel, X509Certificate certificate)
      throws Exception {
    Promise<X509Certificate> unusedPromise =
        channel.attr(CLIENT_CERTIFICATE_PROMISE_KEY).get().setSuccess(certificate);
  }

  private void setHandshakeSuccess() throws Exception {
    setHandshakeSuccess(channel, clientCertificate);
  }

  private void setHandshakeFailure(EmbeddedChannel channel) throws Exception {
    Promise<X509Certificate> unusedPromise =
        channel
            .attr(CLIENT_CERTIFICATE_PROMISE_KEY)
            .get()
            .setFailure(new Exception("Handshake Failure"));
  }

  private void setHandshakeFailure() throws Exception {
    setHandshakeFailure(channel);
  }

  private FullHttpRequest makeEppHttpRequest(String content, Cookie... cookies) {
    return TestUtils.makeEppHttpRequest(
        content,
        RELAY_HOST,
        RELAY_PATH,
        ACCESS_TOKEN,
        getCertificateHash(clientCertificate),
        CLIENT_ADDRESS,
        cookies);
  }

  @Before
  public void setUp() throws Exception {
    clientCertificate = new SelfSignedCertificate().cert();
    channel = setUpNewChannel(eppServiceHandler);
  }

  private EmbeddedChannel setUpNewChannel(EppServiceHandler handler) throws Exception {
    return new EmbeddedChannel(
        DefaultChannelId.newInstance(),
        new ChannelInitializer<EmbeddedChannel>() {
          @Override
          protected void initChannel(EmbeddedChannel ch) throws Exception {
            ch.attr(REMOTE_ADDRESS_KEY).set(CLIENT_ADDRESS);
            ch.attr(CLIENT_CERTIFICATE_PROMISE_KEY).set(ch.eventLoop().newPromise());
            ch.pipeline().addLast(handler);
          }
        });
  }

  @Test
  public void testSuccess_connectionMetrics_oneConnection() throws Exception {
    setHandshakeSuccess();
    String certHash = getCertificateHash(clientCertificate);
    assertThat(channel.isActive()).isTrue();
    verify(metrics).registerActiveConnection(PROTOCOL, certHash, channel);
    verifyNoMoreInteractions(metrics);
  }

  @Test
  public void testSuccess_connectionMetrics_twoConnections_sameClient() throws Exception {
    setHandshakeSuccess();
    String certHash = getCertificateHash(clientCertificate);
    assertThat(channel.isActive()).isTrue();

    // Setup the second channel.
    EppServiceHandler eppServiceHandler2 =
        new EppServiceHandler(
            RELAY_HOST,
            RELAY_PATH,
            () -> ACCESS_TOKEN,
            HELLO.getBytes(UTF_8),
            metrics);
    EmbeddedChannel channel2 = setUpNewChannel(eppServiceHandler2);
    setHandshakeSuccess(channel2, clientCertificate);

    assertThat(channel2.isActive()).isTrue();

    verify(metrics).registerActiveConnection(PROTOCOL, certHash, channel);
    verify(metrics).registerActiveConnection(PROTOCOL, certHash, channel2);
    verifyNoMoreInteractions(metrics);
  }

  @Test
  public void testSuccess_connectionMetrics_twoConnections_differentClients() throws Exception {
    setHandshakeSuccess();
    String certHash = getCertificateHash(clientCertificate);
    assertThat(channel.isActive()).isTrue();

    // Setup the second channel.
    EppServiceHandler eppServiceHandler2 =
        new EppServiceHandler(
            RELAY_HOST,
            RELAY_PATH,
            () -> ACCESS_TOKEN,
            HELLO.getBytes(UTF_8),
            metrics);
    EmbeddedChannel channel2 = setUpNewChannel(eppServiceHandler2);
    X509Certificate clientCertificate2 = new SelfSignedCertificate().cert();
    setHandshakeSuccess(channel2, clientCertificate2);
    String certHash2 = getCertificateHash(clientCertificate2);

    assertThat(channel2.isActive()).isTrue();

    verify(metrics).registerActiveConnection(PROTOCOL, certHash, channel);
    verify(metrics).registerActiveConnection(PROTOCOL, certHash2, channel2);
    verifyNoMoreInteractions(metrics);
  }

  @Test
  public void testSuccess_sendHelloUponHandshakeSuccess() throws Exception {
    // Nothing to pass to the next handler.
    assertThat((Object) channel.readInbound()).isNull();
    setHandshakeSuccess();
    // hello bytes should be passed to the next handler.
    FullHttpRequest helloRequest = channel.readInbound();
    assertThat(helloRequest).isEqualTo(makeEppHttpRequest(HELLO));
    // Nothing further to pass to the next handler.
    assertThat((Object) channel.readInbound()).isNull();
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  public void testSuccess_disconnectUponHandshakeFailure() throws Exception {
    // Nothing to pass to the next handler.
    assertThat((Object) channel.readInbound()).isNull();
    setHandshakeFailure();
    assertThat(channel.isActive()).isFalse();
  }

  @Test
  public void testSuccess_sendRequestToNextHandler() throws Exception {
    setHandshakeSuccess();
    // First inbound message is hello.
    channel.readInbound();
    String content = "<epp>stuff</epp>";
    channel.writeInbound(Unpooled.wrappedBuffer(content.getBytes(UTF_8)));
    FullHttpRequest request = channel.readInbound();
    assertThat(request).isEqualTo(makeEppHttpRequest(content));
    // Nothing further to pass to the next handler.
    assertThat((Object) channel.readInbound()).isNull();
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  public void testSuccess_sendResponseToNextHandler() throws Exception {
    setHandshakeSuccess();
    String content = "<epp>stuff</epp>";
    channel.writeOutbound(makeEppHttpResponse(content, HttpResponseStatus.OK));
    ByteBuf response = channel.readOutbound();
    assertThat(response).isEqualTo(Unpooled.wrappedBuffer(content.getBytes(UTF_8)));
    // Nothing further to pass to the next handler.
    assertThat((Object) channel.readOutbound()).isNull();
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  public void testSuccess_sendResponseToNextHandler_andDisconnect() throws Exception {
    setHandshakeSuccess();
    String content = "<epp>stuff</epp>";
    HttpResponse response = makeEppHttpResponse(content, HttpResponseStatus.OK);
    response.headers().set("Epp-Session", "close");
    channel.writeOutbound(response);
    ByteBuf expectedResponse = channel.readOutbound();
    assertThat(Unpooled.wrappedBuffer(content.getBytes(UTF_8))).isEqualTo(expectedResponse);
    // Nothing further to pass to the next handler.
    assertThat((Object) channel.readOutbound()).isNull();
    // Channel is disconnected.
    assertThat(channel.isActive()).isFalse();
  }

  @Test
  public void testFailure_disconnectOnNonOKResponseStatus() throws Exception {
    setHandshakeSuccess();
    String content = "<epp>stuff</epp>";
    EncoderException thrown =
        assertThrows(
            EncoderException.class,
            () ->
                channel.writeOutbound(
                    makeEppHttpResponse(content, HttpResponseStatus.BAD_REQUEST)));
    assertThat(Throwables.getRootCause(thrown)).isInstanceOf(NonOkHttpResponseException.class);
    assertThat(thrown).hasMessageThat().contains(HttpResponseStatus.BAD_REQUEST.toString());
    assertThat((Object) channel.readOutbound()).isNull();
    assertThat(channel.isActive()).isFalse();
  }

  @Test
  public void testSuccess_setCookies() throws Exception {
    setHandshakeSuccess();
    // First inbound message is hello.
    channel.readInbound();
    String responseContent = "<epp>response</epp>";
    Cookie cookie1 = new DefaultCookie("name1", "value1");
    Cookie cookie2 = new DefaultCookie("name2", "value2");
    channel.writeOutbound(
        makeEppHttpResponse(responseContent, HttpResponseStatus.OK, cookie1, cookie2));
    ByteBuf response = channel.readOutbound();
    assertThat(response).isEqualTo(Unpooled.wrappedBuffer(responseContent.getBytes(UTF_8)));
    String requestContent = "<epp>request</epp>";
    channel.writeInbound(Unpooled.wrappedBuffer(requestContent.getBytes(UTF_8)));
    FullHttpRequest request = channel.readInbound();
    assertHttpRequestEquivalent(request, makeEppHttpRequest(requestContent, cookie1, cookie2));
    // Nothing further to pass to the next handler.
    assertThat((Object) channel.readInbound()).isNull();
    assertThat((Object) channel.readOutbound()).isNull();
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  public void testSuccess_updateCookies() throws Exception {
    setHandshakeSuccess();
    // First inbound message is hello.
    channel.readInbound();
    String responseContent1 = "<epp>response1</epp>";
    Cookie cookie1 = new DefaultCookie("name1", "value1");
    Cookie cookie2 = new DefaultCookie("name2", "value2");
    // First response written.
    channel.writeOutbound(
        makeEppHttpResponse(responseContent1, HttpResponseStatus.OK, cookie1, cookie2));
    channel.readOutbound();
    String requestContent1 = "<epp>request1</epp>";
    // First request written.
    channel.writeInbound(Unpooled.wrappedBuffer(requestContent1.getBytes(UTF_8)));
    FullHttpRequest request1 = channel.readInbound();
    assertHttpRequestEquivalent(request1, makeEppHttpRequest(requestContent1, cookie1, cookie2));
    String responseContent2 = "<epp>response2</epp>";
    Cookie cookie3 = new DefaultCookie("name3", "value3");
    Cookie newCookie2 = new DefaultCookie("name2", "newValue");
    // Second response written.
    channel.writeOutbound(
        makeEppHttpResponse(responseContent2, HttpResponseStatus.OK, cookie3, newCookie2));
    channel.readOutbound();
    String requestContent2 = "<epp>request2</epp>";
    // Second request written.
    channel.writeInbound(Unpooled.wrappedBuffer(requestContent2.getBytes(UTF_8)));
    FullHttpRequest request2 = channel.readInbound();
    // Cookies in second request should be updated.
    assertHttpRequestEquivalent(
        request2, makeEppHttpRequest(requestContent2, cookie1, newCookie2, cookie3));
    // Nothing further to pass to the next handler.
    assertThat((Object) channel.readInbound()).isNull();
    assertThat((Object) channel.readOutbound()).isNull();
    assertThat(channel.isActive()).isTrue();
  }
}
