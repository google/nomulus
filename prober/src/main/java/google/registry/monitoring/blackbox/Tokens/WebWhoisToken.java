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

package google.registry.monitoring.blackbox.Tokens;

import google.registry.monitoring.blackbox.messages.HttpRequestMessage;
import google.registry.monitoring.blackbox.messages.OutboundMessageType;


/**
 * {@link Token} subtype that deals performs specified actions for the WebWhois sequence
 */
public class WebWhoisToken extends Token {
  private static final String PREFIX = "whois.nic.";
  private String name;
  private String host;

  /** Initialized via TLD name */
  public WebWhoisToken(String tld) {
    name = tld;
    host = PREFIX + name;
  }

  /**TODO: sequentially get each TLD in order and on each call to next, pass in next one in the sequence*/
  @Override
  public Token next() {
    return new WebWhoisToken(name);
  }

  /** Modifies the message to reflect the new host */
  @Override
  public OutboundMessageType modifyMessage(OutboundMessageType original) {
    HttpRequestMessage request = (HttpRequestMessage) original;
    request.headers().set("host", host);

    return request;
  }

  @Override
  public String getHost() {
    return host;
  }
}
