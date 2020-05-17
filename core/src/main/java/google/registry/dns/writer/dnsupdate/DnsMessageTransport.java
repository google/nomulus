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

package google.registry.dns.writer.dnsupdate;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.Ints;
import google.registry.config.RegistryConfig.Config;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import javax.inject.Inject;
import javax.net.SocketFactory;
import org.joda.time.Duration;
import org.xbill.DNS.Message;
import org.xbill.DNS.Opcode;

/**
 * A transport for DNS messages. Sends/receives DNS messages over TCP using old-style {@link Socket}
 * s and the message framing defined in <a href="https://tools.ietf.org/html/rfc1035">RFC 1035</a>.
 * We would like use the dnsjava library's {@link org.xbill.DNS.SimpleResolver} class for this, but
 * it requires {@link java.nio.channels.SocketChannel} which is not supported on AppEngine.
 */
public class DnsMessageTransport {

  /**
   * Size of message length field for DNS TCP transport.
   *
   * @see <a href="https://tools.ietf.org/html/rfc1035">RFC 1035</a>
   */
  static final int MESSAGE_LENGTH_FIELD_BYTES = 2;
  private static final int MESSAGE_MAXIMUM_LENGTH = (1 << (MESSAGE_LENGTH_FIELD_BYTES * 8)) - 1;

  /**
   * The standard DNS port number.
   *
   * @see <a href="https://tools.ietf.org/html/rfc1035">RFC 1035</a>
   */
  @VisibleForTesting static final int DNS_PORT = 53;

  private final SocketFactory factory;
  private final String updateHost;
  private final int updateTimeout;

  /**
   * Class constructor.
   *
   * @param factory a factory for TCP sockets
   * @param updateHost host name of the DNS server
   * @param updateTimeout update I/O timeout
   */
  @Inject
  public DnsMessageTransport(
      SocketFactory factory,
      @Config("dnsUpdateHost") String updateHost,
      @Config("dnsUpdateTimeout") Duration updateTimeout) {
    this.factory = factory;
    this.updateHost = updateHost;
    this.updateTimeout = Ints.checkedCast(updateTimeout.getMillis());
  }

  /**
   * Sends a DNS "query" message (most likely an UPDATE) and returns the response. The response is
   * checked for matching ID and opcode.
   *
   * @param query a message to send
   * @return the response received from the server
   * @throws IOException if the Socket input/output streams throws one
   * @throws IllegalArgumentException if the query is too large to be sent (&gt; 65535 bytes)
   */
  public Message send(Message query) throws IOException {
    try (Socket socket = factory.createSocket(InetAddress.getByName(updateHost), DNS_PORT)) {
      socket.setSoTimeout(updateTimeout);
      writeMessage(socket.getOutputStream(), query);
      Message response = readMessage(socket.getInputStream());
      checkValidResponse(query, response);
      return response;
    }
  }

  private void checkValidResponse(Message query, Message response) {
    verify(
        response.getHeader().getID() == query.getHeader().getID(),
        "response ID %s does not match query ID %s",
        response.getHeader().getID(),
        query.getHeader().getID());
    verify(
        response.getHeader().getOpcode() == query.getHeader().getOpcode(),
        "response opcode '%s' does not match query opcode '%s'",
        Opcode.string(response.getHeader().getOpcode()),
        Opcode.string(query.getHeader().getOpcode()));
  }

  private void writeMessage(OutputStream outputStream, Message message) throws IOException {
    byte[] messageData = message.toWire();
    checkArgument(
        messageData.length <= MESSAGE_MAXIMUM_LENGTH,
        "DNS request message larger than maximum of %s: %s",
        MESSAGE_MAXIMUM_LENGTH,
        messageData.length);
    ByteBuffer buffer = ByteBuffer.allocate(messageData.length + MESSAGE_LENGTH_FIELD_BYTES);
    buffer.putShort((short) messageData.length);
    buffer.put(messageData);
    outputStream.write(buffer.array());
  }

  private Message readMessage(InputStream inputStream) throws IOException {
    DataInputStream stream = new DataInputStream(inputStream);
    int length = stream.readUnsignedShort();
    byte[] messageData = new byte[length];
    stream.readFully(messageData);
    return new Message(messageData);
  }
}
