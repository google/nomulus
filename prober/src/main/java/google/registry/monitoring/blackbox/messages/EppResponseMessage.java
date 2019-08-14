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

package google.registry.monitoring.blackbox.messages;

import google.registry.monitoring.blackbox.exceptions.FailureException;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.function.BiFunction;

/**
 * {@link EppMessage} subclass that implements {@link InboundMessageType}, which represents an
 * inbound EPP message and serves to verify the response received from the server.
 *
 * <p>There are 4 specific types of this {@link EppRequestMessage}, which represent the expected
 * successful response types: SimpleSuccess, Greeting, DomainExists, DomainNotExists. The times in
 * which each are expected is explained in the Javadoc for each subclass.
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
  private String expectedClTRID;

  /** Verifies that the response recorded is what we expect from the request sent. */
  public void verify() throws FailureException {
    verifyEppResponse(message, getCheckList.apply(expectedClTRID, expectedDomainName), true);
  }

  /** Extracts {@link org.w3c.dom.Document} from the {@link ByteBuf} input. */
  public void getDocument(ByteBuf buf) throws FailureException {
    // Convert ByteBuf to byte array.
    byte[] response = new byte[buf.readableBytes()];
    buf.readBytes(response);

    // Convert byte array to Document.
    message = byteArrayToXmlDoc(response);
  }

  /** Updates {@code expectedClTRID} and {@code expectedDomainName} fields. */
  void updateInformation(String expectedClTRID, String expectedDomainName) {
    this.expectedClTRID = expectedClTRID;
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
/*
  /**
   * {@link EppResponseMessage} subclass that represents basic successful response containing only
   * the {@code clTRID}.
   *
   * <p>Is the expected response for {@link EppRequestMessage.Login}, {@link
   * EppRequestMessage.Create}, and {@link EppRequestMessage.Delete} when we expect successes and is
   * always the expected response for {@link EppRequestMessage.Logout}.
  public static class SimpleSuccess extends EppResponseMessage {

    @Inject
    public SimpleSuccess() {}

    /** Verifies document structure, successful result code, and accurate clTRID.
    @Override
    public void verify() throws FailureException {
      verifyEppResponse(
          message,
          ImmutableList.of(
              String.format("//eppns:clTRID[.='%s']", expectedClTRID), XPASS_EXPRESSION),
          true);
    }
  }

  /**
   * {@link EppResponseMessage} subclass that represents reponse containing both the {@code clTRID}
   * and {@code domainName}.
   *
   * <p>Is the expected response for {@link EppRequestMessage.Check} when we expect the domain to
   * exist on the server.
  public static class DomainExists extends EppResponseMessage {

    @Inject
    public DomainExists() {}

    /** Verifies document structure, result code, clTRID, and that the domainName exists.
    @Override
    public void verify() throws FailureException {
      verifyEppResponse(
          message,
          ImmutableList.of(
              String.format("//eppns:clTRID[.='%s']", expectedClTRID),
              String.format("//domainns:name[@avail='false'][.='%s']", expectedDomainName),
              XPASS_EXPRESSION),
          true);
    }
  }

  /**
   * {@link EppResponseMessage} subclass that represents reponse containing both the {@code clTRID}
   * and {@code domainName}.
   *
   * <p>Is the expected response for {@link EppRequestMessage.Check} when we expect the domain to
   * not exist on the server.
  public static class DomainNotExists extends EppResponseMessage {

    @Inject
    public DomainNotExists() {}

    /** Verifies document structure, result code, clTRID, and that the domainName doesn't exist.
    @Override
    public void verify() throws FailureException {
      verifyEppResponse(
          message,
          ImmutableList.of(
              String.format("//eppns:clTRID[.='%s']", expectedClTRID),
              String.format("//domainns:name[@avail='true'][.='%s']", expectedDomainName),
              XPASS_EXPRESSION),
          true);
    }
  }

  /**
   * {@link EppResponseMessage} subclass that represents the greeting sent by the server to prober
   * after initial connection established.
   *
   * <p>Is the expected response for a successful {@link EppRequestMessage.Hello}.
  public static class Greeting extends EppResponseMessage {

    @Inject
    public Greeting() {}

    /** Verifies document structure and that the type is a greeting.
    @Override
    public void verify() throws FailureException {
      verifyEppResponse(message, ImmutableList.of("//eppns:greeting"), true);
    }
  }

  /**
   * {@link EppResponseMessage} subclass that represents a failure in the server completing a
   * command.
   *
   * <p>Is the expected response for {@link EppRequestMessage.Login}, {@link
   * EppRequestMessage.Create}, and {@link EppRequestMessage.Delete} when we expect failures.
  public static class Failure extends EppResponseMessage {

    @Inject
    public Failure() {}

    /** Verifies document structure and that the type is a greeting.
    @Override
    public void verify() throws FailureException {
      verifyEppResponse(
          message,
          ImmutableList.of(
              String.format("//eppns:clTRID[.='%s']", expectedClTRID), XFAIL_EXPRESSION),
          true);
    }
  }
}

 */
