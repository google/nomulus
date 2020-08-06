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
import static google.registry.proxy.TestUtils.assertHttpRequestEquivalent;
import static google.registry.proxy.TestUtils.assertHttpResponseEquivalent;
import static google.registry.proxy.TestUtils.makeHttpPostRequest;
import static google.registry.proxy.TestUtils.makeHttpResponse;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for {@link HttpsRelayProtocolModule}.
 *
 * <p>This protocol defines a connection in which the proxy behaves as a standard http client (sans
 * the relay operation which is excluded in end-to-end testing). Because non user-defined handlers
 * are used, the tests here focus on verifying that the request written to the network socket by the
 * client is reconstructed faithfully by a server, and vice versa, that the response the client
 * decoded from incoming bytes is equivalent to the response sent by the server.
 *
 * <p>These tests only ensure that the client represented by this protocol is compatible with a
 * server implementation provided by Netty itself. They test the self-consistency of various Netty
 * handlers that deal with HTTP protocol, but not whether the handlers converts between bytes and
 * HTTP messages correctly, which is presumed correct.
 */
class HttpsRelayProtocolModuleTest extends ProtocolModuleTest {

  private static final String HOST = "test.tld";
  private static final String PATH = "/path/to/test";
  private static final String CONTENT = "content to test\nnext line\n";

  private final EmbeddedChannel serverChannel =
      new EmbeddedChannel(new HttpServerCodec(), new HttpObjectAggregator(512 * 1024));

  HttpsRelayProtocolModuleTest() {
    super(TestComponent::httpsRelayHandlers);
  }

  /**
   * Tests that the client converts given {@link FullHttpRequest} to bytes, which is sent to the
   * server and reconstructed to a {@link FullHttpRequest} that is equivalent to the original. Then
   * test that the server converts given {@link FullHttpResponse} to bytes, which is sent to the
   * client and reconstructed to a {@link FullHttpResponse} that is equivalent to the original.
   *
   * <p>The request and response equivalences are tested in the same method because the client codec
   * tries to pair the response it receives with the request it sends. Receiving a response without
   * sending a request first will cause the {@link HttpObjectAggregator} to fail to aggregate
   * properly.
   */
  private void requestAndRespondWithStatus(HttpResponseStatus status) {
    ByteBuf buffer;
    FullHttpRequest requestSent = makeHttpPostRequest(CONTENT, HOST, PATH);
    // Need to send a copy as the content read index will advance after the request is written to
    // the outbound of client channel, making comparison with requestReceived fail.
    assertThat(channel.writeOutbound(requestSent.copy())).isTrue();
    buffer = channel.readOutbound();
    assertThat(serverChannel.writeInbound(buffer)).isTrue();
    FullHttpRequest requestReceived = serverChannel.readInbound();
    // Verify that the request received is the same as the request sent.
    assertHttpRequestEquivalent(requestSent, requestReceived);

    FullHttpResponse responseSent = makeHttpResponse(CONTENT, status);
    assertThat(serverChannel.writeOutbound(responseSent.copy())).isTrue();
    buffer = serverChannel.readOutbound();
    assertThat(channel.writeInbound(buffer)).isTrue();
    FullHttpResponse responseReceived = channel.readInbound();
    // Verify that the request received is the same as the request sent.
    assertHttpResponseEquivalent(responseSent, responseReceived);
  }

  @Test
  void testSuccess_OkResponse() {
    requestAndRespondWithStatus(HttpResponseStatus.OK);
  }

  @Test
  void testSuccess_NonOkResponse() {
    requestAndRespondWithStatus(HttpResponseStatus.BAD_REQUEST);
  }
}
