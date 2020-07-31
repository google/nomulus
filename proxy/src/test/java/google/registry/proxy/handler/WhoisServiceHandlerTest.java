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
import static google.registry.proxy.TestUtils.makeWhoisHttpRequest;
import static google.registry.proxy.TestUtils.makeWhoisHttpResponse;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.base.Throwables;
import google.registry.proxy.handler.HttpsRelayServiceHandler.NonOkHttpResponseException;
import google.registry.proxy.metric.FrontendMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link WhoisServiceHandler}. */
@RunWith(JUnit4.class)
public class WhoisServiceHandlerTest {

  private static final String RELAY_HOST = "www.example.tld";
  private static final String RELAY_PATH = "/test";
  private static final String QUERY_CONTENT = "test.tld";
  private static final String ACCESS_TOKEN = "this.access.token";
  private static final String PROTOCOL = "whois";
  private static final String CLIENT_HASH = "none";

  private final FrontendMetrics metrics = mock(FrontendMetrics.class);

  private final WhoisServiceHandler whoisServiceHandler =
      new WhoisServiceHandler(RELAY_HOST, RELAY_PATH, () -> ACCESS_TOKEN, metrics);
  private EmbeddedChannel channel;

  @Before
  public void setUp() {
    // Need to reset metrics for each test method, since they are static fields on the class and
    // shared between each run.
    channel = new EmbeddedChannel(whoisServiceHandler);
  }

  @Test
  public void testSuccess_connectionMetrics_oneChannel() {
    assertThat(channel.isActive()).isTrue();
    verify(metrics).registerActiveConnection(PROTOCOL, CLIENT_HASH, channel);
    verifyNoMoreInteractions(metrics);
  }

  @Test
  public void testSuccess_ConnectionMetrics_twoConnections() {
    assertThat(channel.isActive()).isTrue();
    verify(metrics).registerActiveConnection(PROTOCOL, CLIENT_HASH, channel);

    // Setup second channel.
    WhoisServiceHandler whoisServiceHandler2 =
        new WhoisServiceHandler(RELAY_HOST, RELAY_PATH, () -> ACCESS_TOKEN, metrics);
    EmbeddedChannel channel2 =
        // We need a new channel id so that it has a different hash code.
        // This only is needed for EmbeddedChannel because it has a dummy hash code implementation.
        new EmbeddedChannel(DefaultChannelId.newInstance(), whoisServiceHandler2);
    assertThat(channel2.isActive()).isTrue();
    verify(metrics).registerActiveConnection(PROTOCOL, CLIENT_HASH, channel2);
    verifyNoMoreInteractions(metrics);
  }

  @Test
  public void testSuccess_fireInboundHttpRequest() {
    ByteBuf inputBuffer = Unpooled.wrappedBuffer(QUERY_CONTENT.getBytes(US_ASCII));
    FullHttpRequest expectedRequest =
        makeWhoisHttpRequest(QUERY_CONTENT, RELAY_HOST, RELAY_PATH, ACCESS_TOKEN);
    // Input data passed to next handler
    assertThat(channel.writeInbound(inputBuffer)).isTrue();
    FullHttpRequest inputRequest = channel.readInbound();
    assertThat(inputRequest).isEqualTo(expectedRequest);
    // The channel is still open, and nothing else is to be read from it.
    assertThat((Object) channel.readInbound()).isNull();
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  public void testSuccess_parseOutboundHttpResponse() {
    String outputString = "line1\r\nline2\r\n";
    FullHttpResponse outputResponse = makeWhoisHttpResponse(outputString, HttpResponseStatus.OK);
    // output data passed to next handler
    assertThat(channel.writeOutbound(outputResponse)).isTrue();
    ByteBuf parsedBuffer = channel.readOutbound();
    assertThat(parsedBuffer.toString(US_ASCII)).isEqualTo(outputString);
    // The channel is still open, and nothing else is to be written to it.
    assertThat((Object) channel.readOutbound()).isNull();
    assertThat(channel.isActive()).isFalse();
  }

  @Test
  public void testFailure_OutboundHttpResponseNotOK() {
    String outputString = "line1\r\nline2\r\n";
    FullHttpResponse outputResponse =
        makeWhoisHttpResponse(outputString, HttpResponseStatus.BAD_REQUEST);
    EncoderException thrown =
        assertThrows(EncoderException.class, () -> channel.writeOutbound(outputResponse));
    assertThat(Throwables.getRootCause(thrown)).isInstanceOf(NonOkHttpResponseException.class);
    assertThat(thrown).hasMessageThat().contains("400 Bad Request");
    assertThat((Object) channel.readOutbound()).isNull();
    assertThat(channel.isActive()).isFalse();
  }
}
