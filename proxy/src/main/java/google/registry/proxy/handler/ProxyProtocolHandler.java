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

import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.US_ASCII;

import com.google.common.flogger.FluentLogger;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.AttributeKey;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

/**
 * Handler that processes possible existence of a PROXY protocol v1 header.
 *
 * <p>When an EPP client connects to the registry (through the proxy), the registry performs two
 * validations to ensure that only known registrars are allowed. First it checks the sha265 hash of
 * the client SSL certificate and match it to the hash stored in the database for the registrar. It
 * then checks if the connection is from an allow-listed IP address that belongs to that registrar.
 *
 * <p>The proxy receives client connects via the GCP load balancer, which results in the loss of
 * original client IP from the channel. Luckily, the load balancer supports the PROXY protocol v1,
 * which adds a header with source IP information, among other things, to the TCP request at the
 * start of the connection.
 *
 * <p>This handler determines if a connection is proxied (PROXY protocol v1 header present) and
 * correctly sets the source IP address to the channel's attribute regardless of whether it is
 * proxied. After that it removes itself from the channel pipeline because the proxy header is only
 * present at the beginning of the connection.
 *
 * <p>This handler must be the very first handler in a protocol, even before SSL handlers, because
 * PROXY protocol header comes as the very first thing, even before SSL handshake request.
 *
 * @see <a href="https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt">The PROXY protocol</a>
 */
public class ProxyProtocolHandler extends ByteToMessageDecoder {

  /** Key used to retrieve origin IP address from a channel's attribute. */
  public static final AttributeKey<String> REMOTE_ADDRESS_KEY =
      AttributeKey.valueOf("REMOTE_ADDRESS_KEY");

  /**
   * Maximum PROXY protocol v1 header length in bytes including trailing CRLF (HAProxy PROXY
   * protocol v1 specification section 2.1).
   */
  static final int MAX_PROXY_HEADER_LENGTH = 107;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // The proxy header must start with this prefix.
  // Sample header: "PROXY TCP4 255.255.255.255 255.255.255.255 65535 65535\r\n".
  private static final byte[] HEADER_PREFIX = "PROXY".getBytes(US_ASCII);

  private enum DecodeState {
    READING,
    FINISHED,
    FAILED
  }

  private DecodeState decodeState = DecodeState.READING;
  private String proxyHeader = null;

  @Inject
  ProxyProtocolHandler() {}

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    super.channelRead(ctx, msg);
    if (decodeState == DecodeState.FINISHED) {
      String remoteIP;
      if (proxyHeader != null) {
        logger.atFine().log("PROXIED CONNECTION: %s", ctx.channel());
        logger.atFine().log("PROXY HEADER for channel %s: %s", ctx.channel(), proxyHeader);
        String[] headerArray = proxyHeader.split(" ", -1);
        if (headerArray.length == 6) {
          remoteIP = headerArray[2];
          logger.atFine().log(
              "Header parsed, using %s as remote IP for channel %s", remoteIP, ctx.channel());
          // If the header is "PROXY UNKNOWN"
          // (see https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt), likely when the
          // remote connection to the external load balancer is through special means, make it
          // 0.0.0.0 so that it can be treated accordingly by the relevant quota configs.
        } else if (headerArray.length == 2 && headerArray[1].equals("UNKNOWN")) {
          logger.atFine().log(
              "Header parsed, source IP unknown, using 0.0.0.0 as remote IP for channel %s",
              ctx.channel());
          remoteIP = "0.0.0.0";
        } else {
          logger.atFine().log(
              "Cannot parse the header, using source IP as remote IP for channel %s",
              ctx.channel());
          remoteIP = getSourceIP(ctx);
        }
      } else {
        logger.atFine().log(
            "No header present, using source IP directly for channel %s", ctx.channel());
        remoteIP = getSourceIP(ctx);
      }
      if (remoteIP != null) {
        ctx.channel().attr(REMOTE_ADDRESS_KEY).set(remoteIP);
      } else {
        logger.atWarning().log("Not able to obtain remote IP for channel %s", ctx.channel());
      }
      // ByteToMessageDecoder automatically flushes unread bytes in the ByteBuf to the next handler
      // when itself is being removed.
      ctx.pipeline().remove(this);
    }
  }

  private static String getSourceIP(ChannelHandlerContext ctx) {
    SocketAddress remoteAddress = ctx.channel().remoteAddress();
    return (remoteAddress instanceof InetSocketAddress inetSocketAddress)
        ? inetSocketAddress.getAddress().getHostAddress()
        : null;
  }

  /**
   * Attempts to decode an internally accumulated buffer and find the proxy protocol header.
   *
   * <p>When the connection is not proxied (i. e. the initial bytes are not "PROXY"), simply mark
   * {@link DecodeState#FINISHED} and allow the handler to be removed. Otherwise the handler waits
   * until there are enough bytes to parse the header (up to {@link #MAX_PROXY_HEADER_LENGTH} bytes
   * including CRLF), saves the parsed header to {@link #proxyHeader}, and marks {@link
   * DecodeState#FINISHED}. If no CRLF is seen within {@link #MAX_PROXY_HEADER_LENGTH} bytes, the
   * connection is closed immediately to prevent unbounded buffer growth.
   *
   * @param in internally accumulated buffer, newly arrived bytes are appended to it.
   * @param out objects passed to the next handler, in this case nothing is ever passed because the
   *     header itself is processed and written to the attribute of the proxy, and the handler is
   *     then removed from the pipeline.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    if (decodeState != DecodeState.READING) {
      if (decodeState == DecodeState.FAILED) {
        in.skipBytes(in.readableBytes());
      }
      return;
    }
    // Wait until there are more bytes available than the header's length before processing.
    if (in.readableBytes() >= HEADER_PREFIX.length) {
      if (containsHeader(in)) {
        // The inbound message contains the header, it must be a proxied connection. Note that
        // currently proxied connection is only used for EPP protocol, which requires the connection
        // to be SSL enabled. So the beginning of the inbound message upon connection can only be
        // either the proxy header (when proxied), or SSL handshake request (when not proxied),
        // which does not start with "PROXY". Therefore it is safe to assume that if the beginning
        // of the message contains "PROXY", it must be proxied, and must contain \r\n within
        // MAX_PROXY_HEADER_LENGTH bytes.
        int eol = findEndOfLine(in);
        if (eol >= 0) {
          // ByteBuf.readBytes is called so that the header is processed and not passed to handlers
          // further in the pipeline.
          int headerLength = eol - in.readerIndex();
          byte[] headerBytes = new byte[headerLength];
          in.readBytes(headerBytes);
          proxyHeader = new String(headerBytes, US_ASCII);
          // Skip \r\n.
          in.skipBytes(2);
          // Proxy header processed, mark finished so that this handler is removed.
          decodeState = DecodeState.FINISHED;
        } else if (in.readableBytes() >= MAX_PROXY_HEADER_LENGTH) {
          decodeState = DecodeState.FAILED;
          in.skipBytes(in.readableBytes());
          logger.atWarning().log(
              "PROXY protocol v1 header exceeded %d bytes without CRLF for channel %s, closing",
              MAX_PROXY_HEADER_LENGTH, ctx.channel());
          ctx.close();
          throw new TooLongFrameException(
              String.format(
                  "PROXY protocol v1 header exceeded maximum length of %d bytes",
                  MAX_PROXY_HEADER_LENGTH));
        }
      } else {
        // The inbound message does not contain a proxy header, mark finished so that this handler
        // is removed. Note that no inbound bytes are actually processed by this handler because we
        // did not call ByteBuf.readBytes(), but ByteBuf.getByte(), which does not change reader
        // index of the ByteBuf. So any inbound byte is then passed to the next handler to process.
        decodeState = DecodeState.FINISHED;
      }
    }
  }

  /**
   * Returns the index in the buffer of the end of line found within {@link
   * #MAX_PROXY_HEADER_LENGTH} bytes of {@code buffer.readerIndex()}. Returns -1 if no end of line
   * was found within that limit.
   */
  private static int findEndOfLine(final ByteBuf buffer) {
    final int readerIndex = buffer.readerIndex();
    final int maxSearchEnd =
        readerIndex + Math.min(buffer.readableBytes(), MAX_PROXY_HEADER_LENGTH);
    for (int i = readerIndex; i < maxSearchEnd - 1; i++) {
      if (buffer.getByte(i) == '\r' && buffer.getByte(i + 1) == '\n') {
        return i; // \r\n
      }
    }
    return -1; // Not found.
  }

  /** Checks if the given buffer contains the proxy header prefix. */
  private boolean containsHeader(ByteBuf buffer) {
    // The readable bytes is always more or equal to the size of the header prefix because this
    // method is only called when this condition is true.
    checkState(buffer.readableBytes() >= HEADER_PREFIX.length);
    for (int i = 0; i < HEADER_PREFIX.length; ++i) {
      if (buffer.getByte(buffer.readerIndex() + i) != HEADER_PREFIX[i]) {
        return false;
      }
    }
    return true;
  }
}
