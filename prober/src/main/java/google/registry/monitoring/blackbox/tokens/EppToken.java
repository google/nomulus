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

package google.registry.monitoring.blackbox.tokens;

import com.google.common.annotations.VisibleForTesting;
import google.registry.monitoring.blackbox.exceptions.InternalException;
import google.registry.monitoring.blackbox.messages.EppRequestMessage;
import google.registry.monitoring.blackbox.messages.OutboundMessageType;
import io.netty.channel.Channel;

/**
 * {@link Token} subtype that deals performs specified actions for the EPP sequence
 */
public abstract class EppToken extends Token {

  /** Describes the maximum possible length of generated domain name */
  private static final int MAX_DOMAIN_PART_LENGTH = 50;

  /** On every new TRID generated, we increment this static counter to help for added differentiation */
  private static int clientIdSuffix = 0;

  protected final String tld;
  private String host;
  private String currentDomainName;

  /**
   * Always the constructor used to provide any {@link EppToken}, with {@code tld}
   * and {@code host} specified by {@link Dagger}
   */
  protected EppToken(String tld, String host) {
    this.tld = tld;
    this.host = host;
    currentDomainName = newDomainName(getNewTRID());
  }


  /** Constructor used when passing on same {@link Channel} to next {@link Token}. */
  protected EppToken(String tld, String host, Channel channel) {
    this(tld, host);
    channel(channel);
  }

  /** Modifies the message to reflect the new domain name and TRID */
  @Override
  public OutboundMessageType modifyMessage(OutboundMessageType originalMessage) throws InternalException {
    return ((EppRequestMessage) originalMessage).modifyMessage(getNewTRID(), currentDomainName);
  }


  @Override
  public String getHost() {
    return host;
  }

  @VisibleForTesting
  String getCurrentDomainName() {
    return currentDomainName;
  }
  /**
   * Return a unique string usable as an EPP client transaction ID.
   *
   * <p><b>Warning:</b> The prober cleanup servlet relies on the timestamp being in the third
   * position when splitting on dashes. Do not change this format without updating that code as
   * well.</p>
   */
  private synchronized String getNewTRID() {
    return String.format("prober-%s-%d-%d",
        "localhost",
        System.currentTimeMillis(),
        clientIdSuffix++);
  }

  /**
   * Return a fully qualified domain label to use, derived from the client transaction ID.
   */
  private String newDomainName(String clTRID) {
    String sld;
    // not sure if the local hostname will stick to RFC validity rules
    if (clTRID.length() > MAX_DOMAIN_PART_LENGTH) {
      sld = clTRID.substring(clTRID.length() - MAX_DOMAIN_PART_LENGTH);
    } else {
      sld = clTRID;
    }
    //insert top level domain here
    return String.format("%s.%s", sld, tld);
  }


  /**
   * {@link EppToken} Subclass that represents a token used in a transient sequence,
   * meaning the connection is remade on each new iteration of the {@link ProbingSequence}
   */
  public static class Transient extends EppToken {
    public Transient(String tld, String host) {
      super(tld, host);
    }

    @Override
    public Token next() {
      return new Transient(tld, getHost());
    }
  }

  /**
   * {@link EppToken} Subclass that represents a token used in a persistent sequence,
   * meaning the connection is maintained on each new iteration of the {@link ProbingSequence}
   */
  public static class Persistent extends EppToken {
    public Persistent(String tld, String host) {
      super(tld, host);
    }

    private Persistent (String tld, String host, Channel channel) {
      super(tld, host, channel);
    }

    @Override
    public Token next() {
      return new Persistent(tld, getHost(), channel());
    }
  }
}
