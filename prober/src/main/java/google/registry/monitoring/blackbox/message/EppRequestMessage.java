// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

import com.google.common.collect.ImmutableMap;
import google.registry.monitoring.blackbox.exception.EppClientException;
import google.registry.monitoring.blackbox.exception.UndeterminedStateException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * {@link EppMessage} subclass that implements {@link OutboundMessageType}, which represents an
 * outbound Epp message.
 *
 * <p>In modifying the {@code getReplacements} field of {@link EppRequestMessage} and the template,
 * we can represent the the 5 basic EPP commands we are attempting to probe. The original 5 are:
 * LOGIN, CREATE, CHECK, DELETE, LOGOUT.
 *
 * <p>In turn, we equivalently create 10 different EPP commands probed: Hello - checks for Greeting
 * response, Login expecting success, Login expecting Failure, Create expecting Success, Create
 * expecting Failure, Check that the domain exists, Check that the domain doesn't exist, Delete
 * expecting Success, Delete expecting Failure, and Logout expecting Success.
 *
 * <p>The main difference is that we added a hello command that simply waits for the server to send
 * a greeting, then moves on to the Login action.
 *
 * <p>Stores a clTRID and domainName which is modified each time the token calls {@code
 * modifyMessage}. These will also modify the EPP request sent to the server.
 */
public class EppRequestMessage extends EppMessage implements OutboundMessageType {

  /**
   * String that describes the type of EppRequestMessage: hello, login, create, check, delete,
   * logout.
   */
  private String name;

  /** Corresponding {@link EppResponseMessage} that we expect to receive on a successful request. */
  private EppResponseMessage expectedResponse;

  /** Filename for template of current request type. */
  private String template;

  /**
   * {@link ImmutableMap} of replacements that is indicative of each type of {@link
   * EppRequestMessage}
   */
  private BiFunction<String, String, Map<String, String>> getReplacements;

  /**
   * Private constructor for {@link EppRequestMessage} that its subclasses use for instantiation.
   */
  public EppRequestMessage(
      String name,
      EppResponseMessage expectedResponse,
      String template,
      BiFunction<String, String, Map<String, String>> getReplacements) {

    this.name = name;
    this.expectedResponse = expectedResponse;
    this.template = template;
    this.getReplacements = getReplacements;
  }

  /**
   * From the input {@code clTrid} and {@code domainName}, modifies the template EPP XML document
   * and the {@code expectedResponse} to reflect new parameters.
   *
   * @param args - should always be two Strings: The first one is {@code clTrid} and the second one
   *     is {@code domainName}.
   * @return the current {@link EppRequestMessage} instance.
   * @throws EppClientException - On the occasion that the prober can't appropriately modify the EPP
   *     XML document, the blame falls on the prober, not the server, so it throws an {@link
   *     EppClientException}, which is a subclass of the {@link UndeterminedStateException}.
   */
  @Override
  public EppRequestMessage modifyMessage(String... args) throws EppClientException {
    // First argument should always be clTRID.
    String clTrid = args[0];

    // Second argument should always be domainName.
    String domainName = args[1];

    if (template != null) {
      // Checks if we are sending an actual EPP request to the server (if template is null, than
      // we just expect a response.
      try {
        message = getEppDocFromTemplate(template, getReplacements.apply(clTrid, domainName));
      } catch (IOException e) {
        throw new EppClientException(e);
      }
    }
    // Update the EppResponseMessage associated with this EppRequestMessage to reflect changed
    // parameters on this step.
    expectedResponse.updateInformation(clTrid, domainName);
    return this;
  }

  /**
   * Converts the current {@link org.w3c.dom.Document} message to a {@link ByteBuf} with the
   * requisite bytes
   *
   * @return the {@link ByteBuf} instance that stores the bytes representing the requisite EPP
   *     Request
   * @throws EppClientException On the occasion that the prober can't appropriately convert the EPP
   *     XML document to a {@link ByteBuf}, the blame falls on the prober, not the server, so it
   *     throws an {@link EppClientException}, which is a subclass of the {@link
   *     UndeterminedStateException}.
   */
  public ByteBuf bytes() throws EppClientException {
    // obtain byte array of our modified xml document
    byte[] bytestream = xmlDocToByteArray(message);

    // Move bytes to a ByteBuf.
    ByteBuf buf = Unpooled.buffer(bytestream.length);
    buf.writeBytes(bytestream);

    return buf;
  }

  /** Returns the {@link EppResponseMessage} we expect. */
  public EppResponseMessage getExpectedResponse() {
    return expectedResponse;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String responseName() {
    return expectedResponse.name();
  }
}
