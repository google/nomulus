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

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.VerifyException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import javax.net.SocketFactory;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;
import org.xbill.DNS.Update;

/** Unit tests for {@link DnsMessageTransport}. */
@RunWith(JUnit4.class)
public class DnsMessageTransportTest {

  private static final String UPDATE_HOST = "127.0.0.1";

  private final SocketFactory mockFactory = mock(SocketFactory.class);
  private final Socket mockSocket = mock(Socket.class);

  private Message simpleQuery;
  private Message expectedResponse;
  private DnsMessageTransport resolver;
  @Before
  public void before() throws Exception {
    simpleQuery =
        Message.newQuery(Record.newRecord(Name.fromString("example.com."), Type.A, DClass.IN));
    expectedResponse = responseMessageWithCode(simpleQuery, Rcode.NOERROR);
    when(mockFactory.createSocket(InetAddress.getByName(UPDATE_HOST), DnsMessageTransport.DNS_PORT))
        .thenReturn(mockSocket);
    resolver = new DnsMessageTransport(mockFactory, UPDATE_HOST, Duration.ZERO);
  }

  @Test
  public void testSentMessageHasCorrectLengthAndContent() throws Exception {
    ByteArrayInputStream inputStream =
        new ByteArrayInputStream(messageToBytesWithLength(expectedResponse));
    when(mockSocket.getInputStream()).thenReturn(inputStream);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    when(mockSocket.getOutputStream()).thenReturn(outputStream);

    resolver.send(simpleQuery);

    ByteBuffer sentMessage = ByteBuffer.wrap(outputStream.toByteArray());
    int messageLength = sentMessage.getShort();
    byte[] messageData = new byte[messageLength];
    sentMessage.get(messageData);
    assertThat(messageLength).isEqualTo(simpleQuery.toWire().length);
    assertThat(base16().encode(messageData)).isEqualTo(base16().encode(simpleQuery.toWire()));
  }

  @Test
  public void testReceivedMessageWithLengthHasCorrectContent() throws Exception {
    ByteArrayInputStream inputStream =
        new ByteArrayInputStream(messageToBytesWithLength(expectedResponse));
    when(mockSocket.getInputStream()).thenReturn(inputStream);
    when(mockSocket.getOutputStream()).thenReturn(new ByteArrayOutputStream());

    Message actualResponse = resolver.send(simpleQuery);

    assertThat(base16().encode(actualResponse.toWire()))
        .isEqualTo(base16().encode(expectedResponse.toWire()));
  }

  @Test
  public void testEofReceivingResponse() throws Exception {
    byte[] messageBytes = messageToBytesWithLength(expectedResponse);
    ByteArrayInputStream inputStream =
        new ByteArrayInputStream(Arrays.copyOf(messageBytes, messageBytes.length - 1));
    when(mockSocket.getInputStream()).thenReturn(inputStream);
    when(mockSocket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
    assertThrows(EOFException.class, () -> resolver.send(new Message()));
  }

  @Test
  public void testTimeoutReceivingResponse() throws Exception {
    InputStream mockInputStream = mock(InputStream.class);
    when(mockInputStream.read()).thenThrow(new SocketTimeoutException("testing"));
    when(mockSocket.getInputStream()).thenReturn(mockInputStream);
    when(mockSocket.getOutputStream()).thenReturn(new ByteArrayOutputStream());

    Duration testTimeout = Duration.standardSeconds(1);
    DnsMessageTransport resolver = new DnsMessageTransport(mockFactory, UPDATE_HOST, testTimeout);
    Message expectedQuery = new Message();
    assertThrows(SocketTimeoutException.class, () -> resolver.send(expectedQuery));
    verify(mockSocket).setSoTimeout((int) testTimeout.getMillis());
  }

  @Test
  public void testSentMessageTooLongThrowsException() throws Exception {
    Update oversize = new Update(Name.fromString("tld", Name.root));
    for (int i = 0; i < 2000; i++) {
      oversize.add(
          ARecord.newRecord(
              Name.fromString("test-extremely-long-name-" + i + ".tld", Name.root),
              Type.A,
              DClass.IN));
    }
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    when(mockSocket.getOutputStream()).thenReturn(outputStream);
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> resolver.send(oversize));
    assertThat(thrown).hasMessageThat().contains("message larger than maximum");
  }

  @Test
  public void testResponseIdMismatchThrowsExeption() throws Exception {
    expectedResponse.getHeader().setID(1 + simpleQuery.getHeader().getID());
    when(mockSocket.getInputStream())
        .thenReturn(new ByteArrayInputStream(messageToBytesWithLength(expectedResponse)));
    when(mockSocket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
    VerifyException thrown = assertThrows(VerifyException.class, () -> resolver.send(simpleQuery));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "response ID "
                + expectedResponse.getHeader().getID()
                + " does not match query ID "
                + simpleQuery.getHeader().getID());
  }

  @Test
  public void testResponseOpcodeMismatchThrowsException() throws Exception {
    simpleQuery.getHeader().setOpcode(Opcode.QUERY);
    expectedResponse.getHeader().setOpcode(Opcode.STATUS);
    when(mockSocket.getInputStream())
        .thenReturn(new ByteArrayInputStream(messageToBytesWithLength(expectedResponse)));
    when(mockSocket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
    VerifyException thrown = assertThrows(VerifyException.class, () -> resolver.send(simpleQuery));
    assertThat(thrown)
        .hasMessageThat()
        .contains("response opcode 'STATUS' does not match query opcode 'QUERY'");
  }

  private Message responseMessageWithCode(Message query, int responseCode) {
    Message message = new Message(query.getHeader().getID());
    message.getHeader().setOpcode(query.getHeader().getOpcode());
    message.getHeader().setFlag(Flags.QR);
    message.getHeader().setRcode(responseCode);
    return message;
  }

  private byte[] messageToBytesWithLength(Message message) {
    byte[] bytes = message.toWire();
    ByteBuffer buffer =
        ByteBuffer.allocate(bytes.length + DnsMessageTransport.MESSAGE_LENGTH_FIELD_BYTES);
    buffer.putShort((short) bytes.length);
    buffer.put(bytes);
    return buffer.array();
  }
}
