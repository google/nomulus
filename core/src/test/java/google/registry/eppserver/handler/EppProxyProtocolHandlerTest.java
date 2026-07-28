// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.eppserver.handler;

import static com.google.common.truth.Truth.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EppProxyProtocolHandlerTest {

  @Test
  void testProxyProtocol_parsesValidHeader() {
    EppProxyProtocolHandler handler = new EppProxyProtocolHandler();
    EmbeddedChannel channel = new EmbeddedChannel(handler);

    String proxyHeader = "PROXY TCP4 192.168.1.1 10.0.0.1 50000 443\r\n";
    ByteBuf buffer = Unpooled.wrappedBuffer(proxyHeader.getBytes(StandardCharsets.US_ASCII));

    channel.writeInbound(buffer);

    String remoteAddress = channel.attr(EppProxyProtocolHandler.REMOTE_ADDRESS_KEY).get();
    assertThat(remoteAddress).isEqualTo("192.168.1.1");
    assertThat(channel.pipeline().get(EppProxyProtocolHandler.class)).isNull();
  }

  @Test
  void testProxyProtocol_unknownHeader() {
    EppProxyProtocolHandler handler = new EppProxyProtocolHandler();
    EmbeddedChannel channel = new EmbeddedChannel(handler);

    String proxyHeader = "PROXY UNKNOWN\r\n";
    ByteBuf buffer = Unpooled.wrappedBuffer(proxyHeader.getBytes(StandardCharsets.US_ASCII));

    channel.writeInbound(buffer);

    String remoteAddress = channel.attr(EppProxyProtocolHandler.REMOTE_ADDRESS_KEY).get();
    assertThat(remoteAddress).isEqualTo("0.0.0.0");
    assertThat(channel.pipeline().get(EppProxyProtocolHandler.class)).isNull();
  }

  @Test
  void testProxyProtocol_noHeader_notProxied() {
    EppProxyProtocolHandler handler = new EppProxyProtocolHandler();
    EmbeddedChannel channel = new EmbeddedChannel(handler);

    String normalData = "NOT_A_PROXY_HEADER";
    ByteBuf buffer = Unpooled.wrappedBuffer(normalData.getBytes(StandardCharsets.US_ASCII));

    channel.writeInbound(buffer);

    String remoteAddress = channel.attr(EppProxyProtocolHandler.REMOTE_ADDRESS_KEY).get();
    // In EmbeddedChannel without remoteAddress mock, getSourceIP returns null
    assertThat(remoteAddress).isNull();
    assertThat(channel.pipeline().get(EppProxyProtocolHandler.class)).isNull();

    ByteBuf passedOn = channel.readInbound();
    assertThat(passedOn.toString(StandardCharsets.US_ASCII)).isEqualTo("NOT_A_PROXY_HEADER");
  }
}
