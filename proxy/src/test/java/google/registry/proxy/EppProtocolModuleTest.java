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
import static google.registry.networking.handler.SslServerInitializer.CLIENT_CERTIFICATE_PROMISE_KEY;
import static google.registry.proxy.handler.ProxyProtocolHandler.REMOTE_ADDRESS_KEY;
import static google.registry.util.ResourceUtils.readResourceBytes;
import static google.registry.util.X509Utils.getCertificateHash;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.base.Throwables;
import google.registry.proxy.handler.HttpsRelayServiceHandler.NonOkHttpResponseException;
import google.registry.testing.FakeClock;
import google.registry.util.SelfSignedCaCertificate;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.util.concurrent.Promise;
import java.security.cert.X509Certificate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** End-to-end tests for {@link EppProtocolModule}. */
class EppProtocolModuleTest extends ProtocolModuleTest {

  private static final int HEADER_LENGTH = 4;

  private static final String CLIENT_ADDRESS = "epp.client.tld";

  private static final byte[] HELLO_BYTES =
      ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
              + "<epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\">\n"
              + "  <hello/>\n"
              + "</epp>\n")
          .getBytes(UTF_8);

  private X509Certificate certificate;

  EppProtocolModuleTest() {
    super(TestComponent::eppHandlers);
  }

  /** Verifies that the epp message content is represented by the buffers. */
  private static void assertBufferRepresentsContent(ByteBuf buffer, byte[] expectedContents) {
    // First make sure that buffer length is expected content length plus header length.
    assertThat(buffer.readableBytes()).isEqualTo(expectedContents.length + HEADER_LENGTH);
    // Then check if the header value is indeed expected content length plus header length.
    assertThat(buffer.readInt()).isEqualTo(expectedContents.length + HEADER_LENGTH);
    // Finally check the buffer contains the expected contents.
    byte[] actualContents = new byte[expectedContents.length];
    buffer.readBytes(actualContents);
    assertThat(actualContents).isEqualTo(expectedContents);
  }

  /**
   * Read all available outbound frames and make a composite {@link ByteBuf} consisting all of them.
   *
   * <p>This is needed because {@link io.netty.handler.codec.LengthFieldPrepender} does not
   * necessary output only one {@link ByteBuf} from one input message. We need to reassemble the
   * frames together in order to obtain the processed message (prepended with length header).
   */
  private static ByteBuf getAllOutboundFrames(EmbeddedChannel channel) {
    ByteBuf combinedBuffer = Unpooled.buffer();
    ByteBuf buffer;
    while ((buffer = channel.readOutbound()) != null) {
      combinedBuffer.writeBytes(buffer);
    }
    return combinedBuffer;
  }

  /** Get a {@link ByteBuf} that represents the raw epp request with the given content. */
  private ByteBuf getByteBufFromContent(byte[] content) {
    ByteBuf buffer = Unpooled.buffer();
    buffer.writeInt(content.length + HEADER_LENGTH);
    buffer.writeBytes(content);
    return buffer;
  }

  private FullHttpRequest makeEppHttpRequest(byte[] content, Cookie... cookies) {
    return TestUtils.makeEppHttpRequest(
        new String(content, UTF_8),
        PROXY_CONFIG.epp.relayHost,
        PROXY_CONFIG.epp.relayPath,
        TestModule.provideFakeAccessToken().get(),
        getCertificateHash(certificate),
        CLIENT_ADDRESS,
        cookies);
  }

  private FullHttpResponse makeEppHttpResponse(byte[] content, Cookie... cookies) {
    return makeEppHttpResponse(content, HttpResponseStatus.OK, cookies);
  }

  private FullHttpResponse makeEppHttpResponse(
      byte[] content, HttpResponseStatus status, Cookie... cookies) {
    return TestUtils.makeEppHttpResponse(new String(content, UTF_8), status, cookies);
  }

  @BeforeEach
  @Override
  void beforeEach() throws Exception {
    testComponent = makeTestComponent(new FakeClock());
    certificate = SelfSignedCaCertificate.create().cert();
    initializeChannel(
        ch -> {
          ch.attr(REMOTE_ADDRESS_KEY).set(CLIENT_ADDRESS);
          ch.attr(CLIENT_CERTIFICATE_PROMISE_KEY).set(ch.eventLoop().newPromise());
          addAllTestableHandlers(ch);
        });
    Promise<X509Certificate> unusedPromise =
        channel.attr(CLIENT_CERTIFICATE_PROMISE_KEY).get().setSuccess(certificate);
  }

  @Test
  void testSuccess_singleFrameInboundMessage() throws Exception {
    // First inbound message is hello.
    assertThat((FullHttpRequest) channel.readInbound()).isEqualTo(makeEppHttpRequest(HELLO_BYTES));

    byte[] inputBytes = readResourceBytes(getClass(), "login.xml").read();

    // Verify inbound message is as expected.
    assertThat(channel.writeInbound(getByteBufFromContent(inputBytes))).isTrue();
    assertThat((FullHttpRequest) channel.readInbound()).isEqualTo(makeEppHttpRequest(inputBytes));

    // Nothing more to read.
    assertThat((Object) channel.readInbound()).isNull();
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testSuccess_SingleFrame_MultipleInboundMessages() throws Exception {
    // First inbound message is hello.
    channel.readInbound();

    byte[] inputBytes1 = readResourceBytes(getClass(), "login.xml").read();
    byte[] inputBytes2 = readResourceBytes(getClass(), "logout.xml").read();

    // Verify inbound messages are as expected.
    assertThat(
            channel.writeInbound(
                Unpooled.wrappedBuffer(
                    getByteBufFromContent(inputBytes1), getByteBufFromContent(inputBytes2))))
        .isTrue();
    assertThat((FullHttpRequest) channel.readInbound()).isEqualTo(makeEppHttpRequest(inputBytes1));
    assertThat((FullHttpRequest) channel.readInbound()).isEqualTo(makeEppHttpRequest(inputBytes2));

    // Nothing more to read.
    assertThat((Object) channel.readInbound()).isNull();
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testSuccess_MultipleFrames_MultipleInboundMessages() throws Exception {
    // First inbound message is hello.
    channel.readInbound();

    byte[] inputBytes1 = readResourceBytes(getClass(), "login.xml").read();
    byte[] inputBytes2 = readResourceBytes(getClass(), "logout.xml").read();
    ByteBuf inputBuffer =
        Unpooled.wrappedBuffer(
            getByteBufFromContent(inputBytes1), getByteBufFromContent(inputBytes2));

    // The first frame does not contain the entire first message because it is missing 4 byte of
    // header length.
    assertThat(channel.writeInbound(inputBuffer.readBytes(inputBytes1.length))).isFalse();

    // The second frame contains the first message, and part of the second message.
    assertThat(channel.writeInbound(inputBuffer.readBytes(inputBytes2.length))).isTrue();
    assertThat((FullHttpRequest) channel.readInbound()).isEqualTo(makeEppHttpRequest(inputBytes1));

    // The third frame contains the rest of the second message.
    assertThat(channel.writeInbound(inputBuffer)).isTrue();
    assertThat((FullHttpRequest) channel.readInbound()).isEqualTo(makeEppHttpRequest(inputBytes2));

    // Nothing more to read.
    assertThat((Object) channel.readInbound()).isNull();
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testSuccess_simpleOutboundMessage() throws Exception {
    // First inbound message is hello.
    channel.readInbound();

    byte[] outputBytes = readResourceBytes(getClass(), "login_response.xml").read();

    // Verify outbound message is as expected.
    assertThat(channel.writeOutbound(makeEppHttpResponse(outputBytes))).isTrue();
    assertBufferRepresentsContent(getAllOutboundFrames(channel), outputBytes);

    // Nothing more to write.
    assertThat((Object) channel.readOutbound()).isNull();
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  void testFailure_nonOkOutboundMessage() throws Exception {
    // First inbound message is hello.
    channel.readInbound();

    byte[] outputBytes = readResourceBytes(getClass(), "login_response.xml").read();

    // Verify outbound message is not written to the peer as the response is not OK.
    EncoderException thrown =
        assertThrows(
            EncoderException.class,
            () ->
                channel.writeOutbound(
                    makeEppHttpResponse(outputBytes, HttpResponseStatus.UNAUTHORIZED)));
    assertThat(Throwables.getRootCause(thrown)).isInstanceOf(NonOkHttpResponseException.class);
    assertThat(thrown).hasMessageThat().contains("401 Unauthorized");
    assertThat((Object) channel.readOutbound()).isNull();

    // Channel is closed.
    assertThat(channel.isActive()).isFalse();
  }

  @Test
  void testSuccess_setAndReadCookies() throws Exception {
    // First inbound message is hello.
    channel.readInbound();

    byte[] outputBytes1 = readResourceBytes(getClass(), "login_response.xml").read();
    Cookie cookie1 = new DefaultCookie("name1", "value1");
    Cookie cookie2 = new DefaultCookie("name2", "value2");

    // Verify outbound message is as expected.
    assertThat(channel.writeOutbound(makeEppHttpResponse(outputBytes1, cookie1, cookie2))).isTrue();
    assertBufferRepresentsContent(getAllOutboundFrames(channel), outputBytes1);

    // Verify inbound message contains cookies.
    byte[] inputBytes1 = readResourceBytes(getClass(), "logout.xml").read();
    assertThat(channel.writeInbound(getByteBufFromContent(inputBytes1))).isTrue();
    assertThat((FullHttpRequest) channel.readInbound())
        .isEqualTo(makeEppHttpRequest(inputBytes1, cookie1, cookie2));

    // Second outbound message change cookies.
    byte[] outputBytes2 = readResourceBytes(getClass(), "logout_response.xml").read();
    Cookie cookie3 = new DefaultCookie("name3", "value3");
    cookie2 = new DefaultCookie("name2", "newValue2");

    // Verify outbound message is as expected.
    assertThat(channel.writeOutbound(makeEppHttpResponse(outputBytes2, cookie2, cookie3))).isTrue();
    assertBufferRepresentsContent(getAllOutboundFrames(channel), outputBytes2);

    // Verify inbound message contains updated cookies.
    byte[] inputBytes2 = readResourceBytes(getClass(), "login.xml").read();
    assertThat(channel.writeInbound(getByteBufFromContent(inputBytes2))).isTrue();
    assertThat((FullHttpRequest) channel.readInbound())
        .isEqualTo(makeEppHttpRequest(inputBytes2, cookie1, cookie2, cookie3));

    // Nothing more to write or read.
    assertThat((Object) channel.readOutbound()).isNull();
    assertThat((Object) channel.readInbound()).isNull();
    assertThat(channel.isActive()).isTrue();
  }
}
