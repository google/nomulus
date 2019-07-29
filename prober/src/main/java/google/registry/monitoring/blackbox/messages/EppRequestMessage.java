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
import google.registry.monitoring.blackbox.exceptions.InternalException;
import google.registry.monitoring.blackbox.tokens.Token;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Named;

/**
 * {@link EppMessage} subclass that implements {@link OutboundMessageType}, which represents
 * an outbound Epp message.
 *
 * <p>There are 7 specific types of this {@link EppRequestMessage}, which represent the 7
 * basic commands of EPP that we are probing, or are necessary to perform. They are:
 * HELLO, LOGIN, CREATE, CHECK, CLAIMSCHECK, DELETE, and LOGOUT</p>
 *
 * <p>Stores a clTRID which is modified each time the token calls {@code modifyMessage}</p>
 */
public abstract class EppRequestMessage extends EppMessage implements OutboundMessageType {

  public final static String DOMAIN_KEY = "//domainns:name";
  public final static String CLIENT_ID_KEY = "//eppns:clID";
  public final static String CLIENT_PASSWORD_KEY = "//eppns:pw";
  public final static String CLIENT_TRID_KEY = "//eppns:clTRID";

  protected ImmutableMap<String, String> replacements;
  protected String clTRID;
  private String template;


  /**
   * From the input clTRID and domainName, modifies the template EPP XML document
   * to reflect new parameters.
   *
   * @param args - should always be two Strings: First one is {@code clTRID},
   * Second one is {@code domainName}
   *
   * @return the current {@link EppRequestMessage} instance
   *
   * @throws InternalException - On the occasion that the prober can't appropriately
   * modify the EPP XML document, the blame falls on the prober, not the server, so it
   * throws an {@link InternalException}
   */
  @Override
  public EppRequestMessage modifyMessage(String... args) throws InternalException {
    this.clTRID = args[0];
    String domain = args[1];
    Map<String, String> nextArguments = ImmutableMap.<String, String>builder()
        .putAll(replacements)
        .put(DOMAIN_KEY, domain)
        .put(CLIENT_TRID_KEY, clTRID)
        .build();
    try {
      message = getEppDocFromTemplate(template, nextArguments);

    } catch (IOException | EppClientException e) {
      throw new InternalException(e);
    }
    return this;
  }

  public String getClTRID() {
    return clTRID;
  }

  private EppRequestMessage(String template, ImmutableMap<String, String> replacements) {
    this.template = template;
    this.replacements = replacements;
  }

  public ByteBuf bytes() throws EppClientException{
    byte[] bytestream = xmlDocToByteArray(message);
    int capacity = HEADER_LENGTH + bytestream.length;

    ByteBuf buf = Unpooled.buffer(capacity);

    buf.writeInt(capacity);
    buf.writeBytes(bytestream);

    return buf;
  }

  public static class HELLO extends EppRequestMessage {

    @Inject
    public HELLO() {
      super(null, null);
    }

    @Override
    public EppRequestMessage modifyMessage(String... args){
      return this;
    }

    @Override
    public String name() {
      return "Hello Action";
    }
  }

  public static class LOGIN extends EppRequestMessage {
    private static final String template = "login.xml";

    @Inject
    public LOGIN(@Named("epp_user_id") String eppClientId, @Named("epp_password") String eppClientPassword) {
      super(
          template,
          ImmutableMap.of(
              CLIENT_ID_KEY, eppClientId,
              CLIENT_PASSWORD_KEY, eppClientPassword
          )
      );
    }

    @Override
    public EppRequestMessage modifyMessage(String... args) throws InternalException {
      this.clTRID = args[0];
      Map<String, String> nextArguments = ImmutableMap.<String, String>builder()
          .putAll(replacements)
          .put(CLIENT_TRID_KEY, clTRID)
          .build();
      try {
        message = getEppDocFromTemplate(template, nextArguments);
      } catch (IOException | EppClientException e) {
        throw new InternalException(e);
      }
      return this;
    }
    @Override
    public String name() {
      return "Login Action";
    }
  }

  public static class CHECK extends EppRequestMessage {
    private static final String template = "check.xml";

    @Inject
    public CHECK() {
      super(
          template,
          ImmutableMap.of()
      );
    }

    @Override
    public String name() {
      return "Check Action";
    }
  }

  public static class CLAIMSCHECK extends EppRequestMessage {
    private static final String template = "claimscheck.xml";

    @Inject
    public CLAIMSCHECK() {
      super(
          template,
          ImmutableMap.of()
      );
    }

    @Override
    public String name() {
      return "Claimscheck Action";
    }
  }

  public static class CREATE extends EppRequestMessage {
    private static final String template = "create.xml";

    @Inject
    public CREATE() {
      super(
          template,
          ImmutableMap.of()
      );
    }
    @Override
    public String name() {
      return "Create Action";
    }
  }

  public static class DELETE extends EppRequestMessage {
    private static final String template = "delete.xml";

    @Inject
    public DELETE() {
      super(
          template,
          ImmutableMap.of()
      );
    }
    @Override
    public String name() {
      return "Delete Action";
    }
  }

  public static class LOGOUT extends EppRequestMessage {
    private static final String template = "logout.xml";

    @Inject
    public LOGOUT() {
      super(
          template,
          ImmutableMap.of()
      );
    }

    @Override
    public EppRequestMessage modifyMessage(String... args) throws InternalException {
      this.clTRID = args[0];
      try {
        message = getEppDocFromTemplate(template, ImmutableMap.of(CLIENT_TRID_KEY, clTRID));
      } catch (IOException | EppClientException e) {
        throw new InternalException(e);
      }
      return this;
    }

    @Override
    public String name() {
      return "Logout Action";
    }
  }



}
