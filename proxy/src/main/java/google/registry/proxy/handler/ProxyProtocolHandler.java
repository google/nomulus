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
import io.netty.util.AttributeKey;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import javax.inject.Inject;

/**
 * Handler that processes possible existence of a PROXY protocol v1 header.
 *
 * <p>When an EPP client connects to the registry (through the proxy), the registry performs two
 * validations to ensure that only known registrars are allowed. First it checks the sha265 hash of
 * the client SSL certificate and match it to the hash stored in datastore for the registrar. It
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

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // The proxy header must start with this prefix.
  // Sample header: "PROXY TCP4 255.255.255.255 255.255.255.255 65535 65535\r\n".
  private static final byte[] HEADER_PREFIX = "PROXY".getBytes(US_ASCII);

  private boolean finished = false;
  private String proxyHeader = null;

  @Inject
  ProxyProtocolHandler() {}

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    super.channelRead(ctx, msg);
    if (finished) {
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
    return (remoteAddress instanceof InetSocketAddress)
        ? ((InetSocketAddress) remoteAddress).getAddress().getHostAddress()
        : null;
  }

  /**
   * Attempts to decode an internally accumulated buffer and find the proxy protocol header.
   *
   * <p>When the connection is not proxied (i. e. the initial bytes are not "PROXY"), simply set
   * {@link #finished} to true and allow the handler to be removed. Otherwise the handler waits
   * until there's enough bytes to parse the header, save the parsed header to {@link #proxyHeader},
   * and then mark {@link #finished}.
   *
   * @param in internally accumulated buffer, newly arrived bytes are appended to it.
   * @param out objects passed to the next handler, in this case nothing is ever passed because the
   *     header itself is processed and written to the attribute of the proxy, and the handler is
   *     then removed from the pipeline.
   */
  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    // Wait until there are more bytes available than the header's length before processing.
    if (in.readableBytes() >= HEADER_PREFIX.length) {
      if (containsHeader(in)) {
        // The inbound message contains the header, it must be a proxied connection. Note that
        // currently proxied connection is only used for EPP protocol, which requires the connection
        // to be SSL enabled. So the beginning of the inbound message upon connection can only be
        // either the proxy header (when proxied), or SSL handshake request (when not proxied),
        // which does not start with "PROXY". Therefore it is safe to assume that if the beginning
        // of the message contains "PROXY", it must be proxied, and must contain \r\n.
        int eol = findEndOfLine(in);
        // If eol is not found, that is because that we do not yet have enough inbound message, do
        // nothing and wait for more bytes to be readable. eol will  eventually be positive because
        // of the reasoning above: The connection starts with "PROXY", so it must be a proxied
        // connection and contain \r\n.
        if (eol >= 0) {
          // ByteBuf.readBytes is called so that the header is processed and not passed to handlers
          // further in the pipeline.
          byte[] headerBytes = new byte[eol];
          in.readBytes(headerBytes);
          proxyHeader = new String(headerBytes, US_ASCII);
          // Skip \r\n.
          in.skipBytes(2);
          // Proxy header processed, mark finished so that this handler is removed.
          finished = true;
        }
      } else {
        // The inbound message does not contain a proxy header, mark finished so that this handler
        // is removed. Note that no inbound bytes are actually processed by this handler because we
        // did not call ByteBuf.readBytes(), but ByteBuf.getByte(), which does not change reader
        // index of the ByteBuf. So any inbound byte is then passed to the next handler to process.
        finished = true;
      }
    }
  }

  /**
   * Returns the index in the buffer of the end of line found. Returns -1 if no end of line was
   * found in the buffer.
   */
  private static int findEndOfLine(final ByteBuf buffer) {
    final int n = buffer.writerIndex();
    for (int i = buffer.readerIndex(); i < n; i++) {
      final byte b = buffer.getByte(i);
      if (b == '\r' && i < n - 1 && buffer.getByte(i + 1) == '\n') {
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
