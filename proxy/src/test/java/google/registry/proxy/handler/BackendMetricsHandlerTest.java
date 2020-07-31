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
import static google.registry.proxy.Protocol.PROTOCOL_KEY;
import static google.registry.proxy.TestUtils.assertHttpRequestEquivalent;
import static google.registry.proxy.TestUtils.assertHttpResponseEquivalent;
import static google.registry.proxy.TestUtils.makeHttpPostRequest;
import static google.registry.proxy.TestUtils.makeHttpResponse;
import static google.registry.proxy.handler.EppServiceHandler.CLIENT_CERTIFICATE_HASH_KEY;
import static google.registry.proxy.handler.RelayHandler.RELAY_CHANNEL_KEY;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.collect.ImmutableList;
import google.registry.proxy.Protocol;
import google.registry.proxy.Protocol.BackendProtocol;
import google.registry.proxy.Protocol.FrontendProtocol;
import google.registry.proxy.metric.BackendMetrics;
import google.registry.testing.FakeClock;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link BackendMetricsHandler}. */
@RunWith(JUnit4.class)
public class BackendMetricsHandlerTest {

  private static final String HOST = "host.tld";
  private static final String CLIENT_CERT_HASH = "blah12345";
  private static final String RELAYED_PROTOCOL_NAME = "frontend protocol";

  private final FakeClock fakeClock = new FakeClock();
  private final BackendMetrics metrics = mock(BackendMetrics.class);
  private final BackendMetricsHandler handler = new BackendMetricsHandler(fakeClock, metrics);

  private final BackendProtocol backendProtocol =
      Protocol.backendBuilder()
          .name("backend protocol")
          .host(HOST)
          .port(1)
          .handlerProviders(ImmutableList.of())
          .build();

  private final FrontendProtocol frontendProtocol =
      Protocol.frontendBuilder()
          .name(RELAYED_PROTOCOL_NAME)
          .port(2)
          .relayProtocol(backendProtocol)
          .handlerProviders(ImmutableList.of())
          .build();

  private EmbeddedChannel channel;

  @Before
  public void setUp() {
    EmbeddedChannel frontendChannel = new EmbeddedChannel();
    frontendChannel.attr(PROTOCOL_KEY).set(frontendProtocol);
    frontendChannel.attr(CLIENT_CERTIFICATE_HASH_KEY).set(CLIENT_CERT_HASH);
    channel =
        new EmbeddedChannel(
            new ChannelInitializer<EmbeddedChannel>() {
              @Override
              protected void initChannel(EmbeddedChannel ch) throws Exception {
                ch.attr(PROTOCOL_KEY).set(backendProtocol);
                ch.attr(RELAY_CHANNEL_KEY).set(frontendChannel);
                ch.pipeline().addLast(handler);
              }
            });
  }

  @Test
  public void testFailure_outbound_wrongType() {
    Object request = new Object();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> channel.writeOutbound(request));
    assertThat(e).hasMessageThat().isEqualTo("Outgoing request must be FullHttpRequest.");
  }

  @Test
  public void testFailure_inbound_wrongType() {
    Object response = new Object();
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> channel.writeInbound(response));
    assertThat(e).hasMessageThat().isEqualTo("Incoming response must be FullHttpResponse.");
  }

  @Test
  public void testSuccess_oneRequest() {
    FullHttpRequest request = makeHttpPostRequest("some content", HOST, "/");
    // outbound message passed to the next handler.
    assertThat(channel.writeOutbound(request)).isTrue();
    assertHttpRequestEquivalent(request, channel.readOutbound());
    verify(metrics)
        .requestSent(RELAYED_PROTOCOL_NAME, CLIENT_CERT_HASH, request.content().readableBytes());
    verifyNoMoreInteractions(metrics);
  }

  @Test
  public void testSuccess_oneRequest_oneResponse() {
    FullHttpRequest request = makeHttpPostRequest("some request", HOST, "/");
    FullHttpResponse response = makeHttpResponse("some response", HttpResponseStatus.OK);
    // outbound message passed to the next handler.
    assertThat(channel.writeOutbound(request)).isTrue();
    assertHttpRequestEquivalent(request, channel.readOutbound());
    fakeClock.advanceOneMilli();
    // inbound message passed to the next handler.
    assertThat(channel.writeInbound(response)).isTrue();
    assertHttpResponseEquivalent(response, channel.readInbound());

    verify(metrics)
        .requestSent(RELAYED_PROTOCOL_NAME, CLIENT_CERT_HASH, request.content().readableBytes());
    verify(metrics)
        .responseReceived(RELAYED_PROTOCOL_NAME, CLIENT_CERT_HASH, response, Duration.millis(1));
    verifyNoMoreInteractions(metrics);
  }

  @Test
  public void testSuccess_badResponse() {
    FullHttpRequest request = makeHttpPostRequest("some request", HOST, "/");
    FullHttpResponse response =
        makeHttpResponse("some bad response", HttpResponseStatus.BAD_REQUEST);
    // outbound message passed to the next handler.
    assertThat(channel.writeOutbound(request)).isTrue();
    assertHttpRequestEquivalent(request, channel.readOutbound());
    fakeClock.advanceOneMilli();
    // inbound message passed to the next handler.
    // Even though the response status is not OK, the metrics handler only logs it and pass it
    // along to the next handler, which handles it.
    assertThat(channel.writeInbound(response)).isTrue();
    assertHttpResponseEquivalent(response, channel.readInbound());

    verify(metrics)
        .requestSent(RELAYED_PROTOCOL_NAME, CLIENT_CERT_HASH, request.content().readableBytes());
    verify(metrics)
        .responseReceived(RELAYED_PROTOCOL_NAME, CLIENT_CERT_HASH, response, Duration.millis(1));
    verifyNoMoreInteractions(metrics);
  }

  @Test
  public void testFailure_responseBeforeRequest() {
    FullHttpResponse response = makeHttpResponse("phantom response", HttpResponseStatus.OK);
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> channel.writeInbound(response));
    assertThat(e).hasMessageThat().isEqualTo("Response received before request is sent.");
  }

  @Test
  public void testSuccess_pipelinedResponses() {
    FullHttpRequest request1 = makeHttpPostRequest("request 1", HOST, "/");
    FullHttpResponse response1 = makeHttpResponse("response 1", HttpResponseStatus.OK);
    FullHttpRequest request2 = makeHttpPostRequest("request 22", HOST, "/");
    FullHttpResponse response2 = makeHttpResponse("response 22", HttpResponseStatus.OK);
    FullHttpRequest request3 = makeHttpPostRequest("request 333", HOST, "/");
    FullHttpResponse response3 = makeHttpResponse("response 333", HttpResponseStatus.OK);

    // First request, time = 0
    assertThat(channel.writeOutbound(request1)).isTrue();
    assertHttpRequestEquivalent(request1, channel.readOutbound());
    DateTime requestTime1 = fakeClock.nowUtc();

    fakeClock.advanceBy(Duration.millis(5));

    // Second request, time = 5
    assertThat(channel.writeOutbound(request2)).isTrue();
    assertHttpRequestEquivalent(request2, channel.readOutbound());
    DateTime requestTime2 = fakeClock.nowUtc();

    fakeClock.advanceBy(Duration.millis(7));

    // First response, time = 12, latency = 12 - 0 = 12
    assertThat(channel.writeInbound(response1)).isTrue();
    assertHttpResponseEquivalent(response1, channel.readInbound());
    DateTime responseTime1 = fakeClock.nowUtc();

    fakeClock.advanceBy(Duration.millis(11));

    // Third request, time = 23
    assertThat(channel.writeOutbound(request3)).isTrue();
    assertHttpRequestEquivalent(request3, channel.readOutbound());
    DateTime requestTime3 = fakeClock.nowUtc();

    fakeClock.advanceBy(Duration.millis(2));

    // Second response, time = 25, latency = 25 - 5 = 20
    assertThat(channel.writeInbound(response2)).isTrue();
    assertHttpResponseEquivalent(response2, channel.readInbound());
    DateTime responseTime2 = fakeClock.nowUtc();

    fakeClock.advanceBy(Duration.millis(4));

    // Third response, time = 29, latency = 29 - 23 = 6
    assertThat(channel.writeInbound(response3)).isTrue();
    assertHttpResponseEquivalent(response3, channel.readInbound());
    DateTime responseTime3 = fakeClock.nowUtc();

    Duration latency1 = new Duration(requestTime1, responseTime1);
    Duration latency2 = new Duration(requestTime2, responseTime2);
    Duration latency3 = new Duration(requestTime3, responseTime3);

    verify(metrics)
        .requestSent(RELAYED_PROTOCOL_NAME, CLIENT_CERT_HASH, request1.content().readableBytes());
    verify(metrics)
        .requestSent(RELAYED_PROTOCOL_NAME, CLIENT_CERT_HASH, request2.content().readableBytes());
    verify(metrics)
        .requestSent(RELAYED_PROTOCOL_NAME, CLIENT_CERT_HASH, request3.content().readableBytes());
    verify(metrics).responseReceived(RELAYED_PROTOCOL_NAME, CLIENT_CERT_HASH, response1, latency1);
    verify(metrics).responseReceived(RELAYED_PROTOCOL_NAME, CLIENT_CERT_HASH, response2, latency2);
    verify(metrics).responseReceived(RELAYED_PROTOCOL_NAME, CLIENT_CERT_HASH, response3, latency3);
    verifyNoMoreInteractions(metrics);
  }
}
