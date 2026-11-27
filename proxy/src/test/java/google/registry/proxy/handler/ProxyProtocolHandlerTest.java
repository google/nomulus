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
import static google.registry.proxy.handler.ProxyProtocolHandler.MAX_PROXY_HEADER_LENGTH;
import static google.registry.proxy.handler.ProxyProtocolHandler.REMOTE_ADDRESS_KEY;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.TooLongFrameException;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ProxyProtocolHandler}. */
class ProxyProtocolHandlerTest {

  private static final String HEADER_TEMPLATE = "PROXY TCP%d %s %s %s %s\r\n";

  private final ProxyProtocolHandler handler = new ProxyProtocolHandler();
  private final EmbeddedChannel channel = new EmbeddedChannel(handler);

  private String header;

  @Test
  void testSuccess_proxyHeaderPresent_singleFrame() {
    header = String.format(HEADER_TEMPLATE, 4, "172.0.0.1", "255.255.255.255", "234", "123");
    String message = "some message";
    // Header processed, rest of the message passed along.
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer((header + message).getBytes(UTF_8))))
        .isTrue();
    assertThat(((ByteBuf) channel.readInbound()).toString(UTF_8)).isEqualTo(message);
    assertThat(channel.attr(REMOTE_ADDRESS_KEY).get()).isEqualTo("172.0.0.1");
    assertThat(channel.pipeline().get(ProxyProtocolHandler.class)).isNull();
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testSuccess_proxyHeaderUnknownSource_singleFrame() {
    header = "PROXY UNKNOWN\r\n";
    String message = "some message";
    // Header processed, rest of the message passed along.
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer((header + message).getBytes(UTF_8))))
        .isTrue();
    assertThat(((ByteBuf) channel.readInbound()).toString(UTF_8)).isEqualTo(message);
    assertThat(channel.attr(REMOTE_ADDRESS_KEY).get()).isEqualTo("0.0.0.0");
    assertThat(channel.pipeline().get(ProxyProtocolHandler.class)).isNull();
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testSuccess_proxyHeaderPresent_multipleFrames() {
    header = String.format(HEADER_TEMPLATE, 4, "172.0.0.1", "255.255.255.255", "234", "123");
    String frame1 = header.substring(0, 4);
    String frame2 = header.substring(4, 7);
    String frame3 = header.substring(7, 15);
    String frame4 = header.substring(15, header.length() - 1);
    String frame5 = header.substring(header.length() - 1) + "some message";
    // Have not had enough bytes to determine the presence of a header, no message passed along.
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(frame1.getBytes(UTF_8)))).isFalse();
    // Have not had enough bytes to determine the end a header, no message passed along.
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(frame2.getBytes(UTF_8)))).isFalse();
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(frame3.getBytes(UTF_8)))).isFalse();
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(frame4.getBytes(UTF_8)))).isFalse();
    // Now there are enough bytes to construct a header.
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(frame5.getBytes(UTF_8)))).isTrue();
    assertThat(((ByteBuf) channel.readInbound()).toString(UTF_8)).isEqualTo("some message");
    assertThat(channel.attr(REMOTE_ADDRESS_KEY).get()).isEqualTo("172.0.0.1");
    assertThat(channel.pipeline().get(ProxyProtocolHandler.class)).isNull();
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testSuccess_proxyHeaderPresent_singleFrame_ipv6() {
    header =
        String.format(HEADER_TEMPLATE, 6, "2001:db8:0:1:1:1:1:1", "0:0:0:0:0:0:0:1", "234", "123");
    String message = "some message";
    // Header processed, rest of the message passed along.
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer((header + message).getBytes(UTF_8))))
        .isTrue();
    assertThat(((ByteBuf) channel.readInbound()).toString(UTF_8)).isEqualTo(message);
    assertThat(channel.attr(REMOTE_ADDRESS_KEY).get()).isEqualTo("2001:db8:0:1:1:1:1:1");
    assertThat(channel.pipeline().get(ProxyProtocolHandler.class)).isNull();
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testSuccess_proxyHeaderNotPresent_singleFrame() {
    String message = "some message";
    // No header present, rest of the message passed along.
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(message.getBytes(UTF_8)))).isTrue();
    assertThat(((ByteBuf) channel.readInbound()).toString(UTF_8)).isEqualTo(message);
    assertThat(channel.attr(REMOTE_ADDRESS_KEY).get()).isNull();
    assertThat(channel.pipeline().get(ProxyProtocolHandler.class)).isNull();
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testSuccess_proxyHeaderNotPresent_multipleFrames() {
    String frame1 = "som";
    String frame2 = "e mess";
    String frame3 = "age\nis not";
    String frame4 = "meant to be good.\n";
    // Have not had enough bytes to determine the presence of a header, no message passed along.
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(frame1.getBytes(UTF_8)))).isFalse();
    // Now we have more than five bytes to determine if it starts with "PROXY"
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(frame2.getBytes(UTF_8)))).isTrue();
    assertThat(((ByteBuf) channel.readInbound()).toString(UTF_8)).isEqualTo(frame1 + frame2);
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(frame3.getBytes(UTF_8)))).isTrue();
    assertThat(((ByteBuf) channel.readInbound()).toString(UTF_8)).isEqualTo(frame3);
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(frame4.getBytes(UTF_8)))).isTrue();
    assertThat(((ByteBuf) channel.readInbound()).toString(UTF_8)).isEqualTo(frame4);
    assertThat(channel.attr(REMOTE_ADDRESS_KEY).get()).isNull();
    assertThat(channel.pipeline().get(ProxyProtocolHandler.class)).isNull();
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testSuccess_proxyHeaderAtExact107ByteCap() {
    // 105-byte header + 2-byte CRLF = 107 bytes (exact HAProxy PROXY protocol v1 cap).
    String paddedHeader =
        "PROXY TCP6 ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff "
            + "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff 65535 65535000\r\n";
    assertThat(paddedHeader.length()).isEqualTo(MAX_PROXY_HEADER_LENGTH);
    String message = "tls-payload";
    assertThat(
            channel.writeInbound(Unpooled.wrappedBuffer((paddedHeader + message).getBytes(UTF_8))))
        .isTrue();
    assertThat(((ByteBuf) channel.readInbound()).toString(UTF_8)).isEqualTo(message);
    assertThat(channel.attr(REMOTE_ADDRESS_KEY).get())
        .isEqualTo("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff");
    assertThat(channel.pipeline().get(ProxyProtocolHandler.class)).isNull();
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testSuccess_validProxyHeaderFollowedByLargePayloadOver107Bytes() {
    header = String.format(HEADER_TEMPLATE, 4, "172.0.0.1", "255.255.255.255", "234", "123");
    String largePayload = "X".repeat(300);
    assertThat(
            channel.writeInbound(Unpooled.wrappedBuffer((header + largePayload).getBytes(UTF_8))))
        .isTrue();
    assertThat(((ByteBuf) channel.readInbound()).toString(UTF_8)).isEqualTo(largePayload);
    assertThat(channel.attr(REMOTE_ADDRESS_KEY).get()).isEqualTo("172.0.0.1");
    assertThat(channel.pipeline().get(ProxyProtocolHandler.class)).isNull();
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testFailure_proxyHeaderExceeds107BytesWithoutCrlf_singleFrame() {
    String oversized = "PROXY " + "A".repeat(MAX_PROXY_HEADER_LENGTH - 6);
    assertThat(oversized.length()).isEqualTo(MAX_PROXY_HEADER_LENGTH);
    assertThrows(
        TooLongFrameException.class,
        () -> channel.writeInbound(Unpooled.wrappedBuffer(oversized.getBytes(UTF_8))));
    assertThat((Object) channel.readInbound()).isNull();
    assertThat(channel.isActive()).isFalse();
  }

  @Test
  void testFailure_proxyHeaderExceeds107BytesWithoutCrlf_multipleFrames() {
    String chunk1 = "PROXY " + "A".repeat(54); // 60 bytes
    String chunk2 = "B".repeat(MAX_PROXY_HEADER_LENGTH - 60); // 47 bytes -> total 107 without CRLF
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(chunk1.getBytes(UTF_8)))).isFalse();
    assertThat(channel.isActive()).isTrue();
    assertThrows(
        TooLongFrameException.class,
        () -> channel.writeInbound(Unpooled.wrappedBuffer(chunk2.getBytes(UTF_8))));
    assertThat((Object) channel.readInbound()).isNull();
    assertThat(channel.isActive()).isFalse();
  }

  @Test
  void testFailure_proxyHeader108BytesWithCrlf_rejected() {
    String oversizedWithCrlf = "PROXY " + "A".repeat(MAX_PROXY_HEADER_LENGTH - 7) + "\r\n";
    assertThat(oversizedWithCrlf.length()).isEqualTo(MAX_PROXY_HEADER_LENGTH + 1);
    assertThrows(
        TooLongFrameException.class,
        () -> channel.writeInbound(Unpooled.wrappedBuffer(oversizedWithCrlf.getBytes(UTF_8))));
    assertThat((Object) channel.readInbound()).isNull();
    assertThat(channel.isActive()).isFalse();
  }
}
