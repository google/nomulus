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
import static google.registry.proxy.handler.EppServiceHandler.CLIENT_CERTIFICATE_HASH_KEY;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.collect.ImmutableList;
import google.registry.proxy.Protocol;
import google.registry.proxy.Protocol.FrontendProtocol;
import google.registry.proxy.metric.FrontendMetrics;
import google.registry.testing.FakeClock;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FrontendMetricsHandler}. */
class FrontendMetricsHandlerTest {

  private static final String CLIENT_CERT_HASH = "blah12345";
  private static final String PROTOCOL_NAME = "frontend protocol";

  private final FakeClock fakeClock = new FakeClock();
  private final FrontendMetrics metrics = mock(FrontendMetrics.class);
  private final FrontendMetricsHandler handler = new FrontendMetricsHandler(fakeClock, metrics);

  private final FrontendProtocol frontendProtocol =
      Protocol.frontendBuilder()
          .name(PROTOCOL_NAME)
          .port(2)
          .hasBackend(false)
          .handlerProviders(ImmutableList.of())
          .build();

  private EmbeddedChannel channel;

  @BeforeEach
  void beforeEach() {
    channel =
        new EmbeddedChannel(
            new ChannelInitializer<EmbeddedChannel>() {
              @Override
              protected void initChannel(EmbeddedChannel ch) {
                ch.attr(PROTOCOL_KEY).set(frontendProtocol);
                ch.attr(CLIENT_CERTIFICATE_HASH_KEY).set(CLIENT_CERT_HASH);
                ch.pipeline().addLast(handler);
              }
            });
  }

  @Test
  void testSuccess_oneRequest() {
    // Inbound message passed to the next handler.
    Object request = new Object();
    assertThat(channel.writeInbound(request)).isTrue();
    assertThat((Object) channel.readInbound()).isEqualTo(request);
    verifyNoInteractions(metrics);
  }

  @Test
  void testSuccess_oneRequest_oneResponse() {
    Object request = new Object();
    Object response = new Object();
    // Inbound message passed to the next handler.
    assertThat(channel.writeInbound(request)).isTrue();
    assertThat((Object) channel.readInbound()).isEqualTo(request);
    fakeClock.advanceOneMilli();
    // Outbound message passed to the next handler.
    assertThat(channel.writeOutbound(response)).isTrue();
    assertThat((Object) channel.readOutbound()).isEqualTo(response);
    // Verify that latency is recorded.
    verify(metrics).responseSent(PROTOCOL_NAME, CLIENT_CERT_HASH, Duration.millis(1));
    verifyNoMoreInteractions(metrics);
  }

  @Test
  void testFailure_responseBeforeRequest() {
    Object response = new Object();
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> channel.writeOutbound(response));
    assertThat(e).hasMessageThat().isEqualTo("Response sent before request is received.");
  }

  @Test
  void testSuccess_pipelinedResponses() {
    Object request1 = new Object();
    Object response1 = new Object();
    Object request2 = new Object();
    Object response2 = new Object();
    Object request3 = new Object();
    Object response3 = new Object();

    // First request, time = 0
    assertThat(channel.writeInbound(request1)).isTrue();
    assertThat((Object) channel.readInbound()).isEqualTo(request1);
    DateTime requestTime1 = fakeClock.nowUtc();

    fakeClock.advanceBy(Duration.millis(5));

    // Second request, time = 5
    assertThat(channel.writeInbound(request2)).isTrue();
    assertThat((Object) channel.readInbound()).isEqualTo(request2);
    DateTime requestTime2 = fakeClock.nowUtc();

    fakeClock.advanceBy(Duration.millis(7));

    // First response, time = 12, latency = 12 - 0 = 12
    assertThat(channel.writeOutbound(response1)).isTrue();
    assertThat((Object) channel.readOutbound()).isEqualTo(response1);
    DateTime responseTime1 = fakeClock.nowUtc();

    fakeClock.advanceBy(Duration.millis(11));

    // Third request, time = 23
    assertThat(channel.writeInbound(request3)).isTrue();
    assertThat((Object) channel.readInbound()).isEqualTo(request3);
    DateTime requestTime3 = fakeClock.nowUtc();

    fakeClock.advanceBy(Duration.millis(2));

    // Second response, time = 25, latency = 25 - 5 = 20
    assertThat(channel.writeOutbound(response2)).isTrue();
    assertThat((Object) channel.readOutbound()).isEqualTo(response2);
    DateTime responseTime2 = fakeClock.nowUtc();

    fakeClock.advanceBy(Duration.millis(4));

    // Third response, time = 29, latency = 29 - 23 = 6
    assertThat(channel.writeOutbound(response3)).isTrue();
    assertThat((Object) channel.readOutbound()).isEqualTo(response3);
    DateTime responseTime3 = fakeClock.nowUtc();

    Duration latency1 = new Duration(requestTime1, responseTime1);
    Duration latency2 = new Duration(requestTime2, responseTime2);
    Duration latency3 = new Duration(requestTime3, responseTime3);

    verify(metrics).responseSent(PROTOCOL_NAME, CLIENT_CERT_HASH, latency1);
    verify(metrics).responseSent(PROTOCOL_NAME, CLIENT_CERT_HASH, latency2);
    verify(metrics).responseSent(PROTOCOL_NAME, CLIENT_CERT_HASH, latency3);
    verifyNoMoreInteractions(metrics);
  }
}
