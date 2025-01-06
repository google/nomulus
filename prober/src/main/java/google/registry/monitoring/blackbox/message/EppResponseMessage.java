// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.monitoring.blackbox.message;

import google.registry.monitoring.blackbox.exception.FailureException;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.function.BiFunction;

/**
 * {@link EppMessage} subclass that implements {@link InboundMessageType}, which represents an
 * inbound EPP message and serves to verify the response received from the server.
 *
 * <p>There are 4 created types of this {@link EppRequestMessage}, which represent the expected
 * successful response types. Their names are: success, failure greeting, domainExists,
 * domainNotExists. Success implies a response declaring the command was completed successfully.
 * Failure implies a response declaring the command was not completed successfully. Greeting is the
 * standard initial response the server sends after a connection is established. DomainExists is a
 * response to a Check request saying the domain exists on the server. DomainNotExists is a response
 * that essentially says the opposite.
 *
 * <p>Stores an expected clTRID and domainName which are the ones used by the {@link
 * EppRequestMessage} pointing to this {@link EppRequestMessage}.
 *
 * <p>From the {@link ByteBuf} input, stores the corresponding {@link org.w3c.dom.Document}
 * represented and to be validated.
 */
public class EppResponseMessage extends EppMessage implements InboundMessageType {

  /**
   * Specifies type of {@link EppResponseMessage}.
   *
   * <p>All possible names are: success, failure, domainExists, domainNotExists, greeting.
   */
  private final String name;

  /** Lambda expression that returns a checkList from input clTRID and domain Strings. */
  private final BiFunction<String, String, List<String>> getCheckList;

  /** Domain name we expect to receive in current response. */
  private String expectedDomainName;

  /** ClTRID we expect to receive in current response. */
  private String expectedClTrid;

  /** Verifies that the response recorded is what we expect from the request sent. */
  public void verify() throws FailureException {
    verifyEppResponse(message, getCheckList.apply(expectedClTrid, expectedDomainName), true);
  }

  /** Extracts {@link org.w3c.dom.Document} from the {@link ByteBuf} input. */
  public void getDocument(ByteBuf buf) throws FailureException {
    // Convert ByteBuf to byte array.
    byte[] response = new byte[buf.readableBytes()];
    buf.readBytes(response);

    // Convert byte array to Document.
    message = byteArrayToXmlDoc(response);
  }

  /** Updates {@code expectedClTrid} and {@code expectedDomainName} fields. */
  void updateInformation(String expectedClTrid, String expectedDomainName) {
    this.expectedClTrid = expectedClTrid;
    this.expectedDomainName = expectedDomainName;
  }

  public EppResponseMessage(String name, BiFunction<String, String, List<String>> getCheckList) {
    this.name = name;
    this.getCheckList = getCheckList;
  }

  public String name() {
    return name;
  }
}
