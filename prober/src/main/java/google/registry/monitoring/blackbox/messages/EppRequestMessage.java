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

import com.google.common.collect.ImmutableMap;
import google.registry.monitoring.blackbox.exceptions.EppClientException;
import google.registry.monitoring.blackbox.exceptions.UndeterminedStateException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Named;

/**
 * {@link EppMessage} subclass that implements {@link OutboundMessageType}, which represents an
 * outbound Epp message.
 *
 * <p>There are 8 specific types of this {@link EppRequestMessage}, which represent the
 * facets of the 5 basic EPP commands we are attempting to probe. The original 6 are:
 * LOGIN, CREATE, CHECK, CLAIMSCHECK, DELETE, LOGOUT.</p>
 *
 * <p>In turn, there are 8 similar types: Hello, Login, Create, CheckExists, CheckNotExists,
 * ClaimsCheck, Delete, and Logout. The only difference is that we create two different
 * types of Check, for when we expect the domain to exist and when we expect it not to.
 * We also added a hello command that simply waits for the server to send a greeting, then
 * moves on to the Login action.</p>
 *
 * <p>Stores a clTRID and domainName which is modified each time the token calls
 * {@code modifyMessage}. These will also modify the EPP request sent to the server.</p>
 */
public abstract class EppRequestMessage extends EppMessage implements OutboundMessageType {

  /** Key that allows for substitution of{@code domainName} to xml template. */
  public final static String DOMAIN_KEY = "//domainns:name";

  /** Key that allows for substitution of epp user id to xml template. */
  public final static String CLIENT_ID_KEY = "//eppns:clID";

  /** Key that allows for substitution of epp password to xml template. */
  public final static String CLIENT_PASSWORD_KEY = "//eppns:pw";

  /** Key that allows for substitution of{@code clTRID} to xml template. */
  public final static String CLIENT_TRID_KEY = "//eppns:clTRID";

  /** Client TRID associated with current request (modified on each call to {@code modifyMessage}. */
  protected String clTRID;

  /** Domain name associated with current request (modified on each call to {@code modifyMessage}. */
  protected String domainName;

  /** Filename for template of current request type. */
  private String template;

  /** Corresponding {@link EppResponseMessage} that we expect to receive on a successful request. */
  private EppResponseMessage expectedResponse;

  private EppRequestMessage(String template) {
    this.template = template;
  }

  /**
   * From the input {@code clTRID} and {@code domainName}, modifies the template EPP XML document
   * to reflect new parameters.
   *
   * @param args - should always be two Strings: The first one is {@code expectedClTRID} and the
   * second one is {@code domainName}.
   *
   * @return the current {@link EppRequestMessage} instance.
   *
   * @throws EppClientException - On the occasion that the prober can't appropriately modify
   * the EPP XML document, the blame falls on the prober, not the server, so it throws an
   * {@link EppClientException}, which is a subclass of the {@link UndeterminedStateException}.
   */
  @Override
  public EppRequestMessage modifyMessage(String... args) throws EppClientException {
    clTRID = args[0];
    domainName = args[1];

    try {
      message = getEppDocFromTemplate(
          template,
          ImmutableMap.of(
              DOMAIN_KEY, domainName,
              CLIENT_TRID_KEY, clTRID));

    } catch (IOException e) {
      throw new EppClientException(e);
    }
    return this;
  }

  /** Private constructor for {@link EppRequestMessage} that its subclasses use for instantiation. */
  private EppRequestMessage(EppResponseMessage expectedResponse, String template) {
    this.expectedResponse = expectedResponse;
    this.template = template;
  }

  /**
   * Converts the current {@link org.w3c.dom.Document} message to a {@link ByteBuf} with the requisite bytes
   *
   * @return the {@link ByteBuf} instance that stores the bytes representing the requisite EPP Request
   *
   * @throws EppClientException- On the occasion that the prober can't appropriately convert the EPP XML
   * document to a {@link ByteBuf}, the blame falls on the prober, not the server, so it throws an
   * {@link EppClientException}, which is a subclass of the {@link UndeterminedStateException}.
   */
  public ByteBuf bytes() throws EppClientException{
    //obtain byte array of our modified xml document
    byte[] bytestream = xmlDocToByteArray(message);

    //standard xml formatting stores capacity first then the document in bytes,
    //so we store
    int capacity = HEADER_LENGTH + bytestream.length;

    ByteBuf buf = Unpooled.buffer(capacity);

    buf.writeInt(capacity);
    buf.writeBytes(bytestream);

    return buf;
  }

  /**
   * Updates expected information in the {@link EppResponseMessage} corresponding to this
   * {@link EppRequestMessage} instance to reflect updated {@code clTRID} and {@code domainName}
   * and returns it.
   */
  public EppResponseMessage getExpectedResponse() {
    expectedResponse.updateInformation(clTRID, domainName);
    return expectedResponse;
  }

  /**
   * {@link EppRequestMessage} subclass that represents initial
   * connection with server.
   *
   * <p>No actual message is sent, but expects back a
   * {@link EppResponseMessage.Greeting} from the server.</p>
   *
   * <p>Constructor takes in only the Dagger provided
   * {@link EppResponseMessage.Greeting}.</p>
   */
  public static class Hello extends EppRequestMessage {
    private final static String template = "hello.xml";

    @Inject
    public Hello(EppResponseMessage.Greeting greetingResponse) {
      super(greetingResponse, template);
    }

    @Override
    public EppRequestMessage modifyMessage(String... args) {
      return this;
    }

    @Override
    public String toString() {
      return "Hello Action";
    }
  }

  /**
   * {@link EppRequestMessage} subclass that represents message sent
   * to login to the EPP server.
   *
   * <p>Expects back a {@link EppResponseMessage.SimpleSuccess} from server.</p>
   *
   * <p>Constructor takes in Dagger provided {@code eppClientId} and
   * {@code eppClientPassword} to login to the server. Additionally,
   * the {@link EppResponseMessage.SimpleSuccess} is provided, as that
   * is what we expect to receive back.</p>
   *
   * <p>Message is modified each time solely to reflect new {@code clTRID}.</p>
   */
  public static class Login extends EppRequestMessage {
    private static final String template = "login.xml";

    /** We store the clientId and password, which are necessary for the login EPP message. */
    private String eppClientId;
    private String eppClientPassword;

    @Inject
    public Login(EppResponseMessage.SimpleSuccess simpleSuccessResponse, @Named("eppUserId") String eppClientId, @Named("eppPassword") String eppClientPassword) {
      super(simpleSuccessResponse, template);

      this.eppClientId = eppClientId;
      this.eppClientPassword = eppClientPassword;
    }

    @Override
    public EppRequestMessage modifyMessage(String... args) throws EppClientException {
      clTRID = args[0];
      domainName = args[1];

      try {
        message = getEppDocFromTemplate(
            template, ImmutableMap.of(
                CLIENT_ID_KEY, eppClientId,
                CLIENT_PASSWORD_KEY, eppClientPassword,
                CLIENT_TRID_KEY, clTRID));

      } catch (IOException e) {
        throw new EppClientException(e);
      }
      return this;
    }

    @Override
    public String toString() {
      return "Login Action";
    }
  }


  /**
   * {@link EppRequestMessage} subclass that represents message sent
   * to check that a given domain exists on the server's EPP records.
   *
   * <p>Expects back a {@link EppResponseMessage.DomainExists} from server.</p>
   *
   * <p>Constructor takes in only Dagger provided
   * {@link EppResponseMessage.DomainExists}.</p>
   *
   * <p>Message is modified using parent {@code modifyMessage}.</p>
   */
  public static class CheckExists extends EppRequestMessage {
    private static final String template = "check.xml";

    @Inject
    public CheckExists(EppResponseMessage.DomainExists domainExistsResponse) {
      super(domainExistsResponse, template);
    }

    @Override
    public String toString() {
      return "Check Exists Action";
    }
  }

  /**
   * {@link EppRequestMessage} subclass that represents message sent
   * to check that a given domain doesn't exist on the server's EPP records.
   *
   * <p>Expects back a {@link EppResponseMessage.DomainNotExists} from server.</p>
   *
   * <p>Constructor takes in only Dagger provided
   * {@link EppResponseMessage.DomainNotExists}.</p>
   *
   * <p>Message is modified using parent {@code modifyMessage}.</p>
   */
  public static class CheckNotExists extends EppRequestMessage {
    private static final String template = "check.xml";

    @Inject
    public CheckNotExists(EppResponseMessage.DomainNotExists domainNotExistsResponse) {
      super(domainNotExistsResponse, template);
    }

    @Override
    public String toString() {
      return "Check Not Exists Action";
    }
  }

  /**
   * {@link EppRequestMessage} subclass that represents message sent
   * to create a new domain.
   *
   * <p>Expects back a {@link EppResponseMessage.SimpleSuccess} from server.</p>
   *
   * <p>Constructor takes in only Dagger provided
   * {@link EppResponseMessage.SimpleSuccess}.</p>
   *
   * <p>Message is modified using parent {@code modifyMessage}.</p>
   */
  public static class Create extends EppRequestMessage {
    private static final String template = "create.xml";

    @Inject
    public Create(EppResponseMessage.SimpleSuccess simpleSuccessResponse) {
      super(simpleSuccessResponse, template);
    }

    @Override
    public String toString() {
      return "Create Action";
    }
  }

  /**
   * {@link EppRequestMessage} subclass that represents message sent
   * to delete records of a given domain.
   *
   * <p>Expects back a {@link EppResponseMessage.SimpleSuccess} from server.</p>
   *
   * <p>Constructor takes in only Dagger provided
   * {@link EppResponseMessage.SimpleSuccess}.</p>
   *
   * <p>Message is modified using parent {@code modifyMessage}.</p>
   */
  public static class Delete extends EppRequestMessage {
    private static final String template = "delete.xml";

    @Inject
    public Delete(EppResponseMessage.SimpleSuccess simpleSuccessResponse) {
      super(simpleSuccessResponse, template);
    }

    @Override
    public String toString() {
      return "Delete Action";
    }
  }

  /**
   * {@link EppRequestMessage} subclass that represents message sent
   * to logout of EPP server.
   *
   * <p>Expects back a {@link EppResponseMessage.SimpleSuccess} from server.</p>
   *
   * <p>Constructor takes in only Dagger provided
   * {@link EppResponseMessage.SimpleSuccess}.</p>
   *
   * <p>Message is modified each time solely to reflect new {@code clTRID}.</p>
   */
  public static class Logout extends EppRequestMessage {
    private static final String template = "logout.xml";

    @Inject
    public Logout(EppResponseMessage.SimpleSuccess simpleSuccessResponse) {
      super(simpleSuccessResponse, template);
    }

    @Override
    public EppRequestMessage modifyMessage(String... args) throws EppClientException {
      clTRID = args[0];
      domainName = args[1];

      try {
        message = getEppDocFromTemplate(template, ImmutableMap.of(CLIENT_TRID_KEY, clTRID));
      } catch (IOException e) {
        throw new EppClientException(e);
      }
      return this;
    }

    @Override
    public String toString() {
      return "Logout Action";
    }
  }


}
