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

import com.google.common.collect.ImmutableList;
import google.registry.monitoring.blackbox.WebWhoisModule.WebWhoisProtocol;
import google.registry.monitoring.blackbox.exceptions.InternalException;
import google.registry.monitoring.blackbox.messages.HttpRequestMessage;
import google.registry.monitoring.blackbox.messages.OutboundMessageType;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Named;

/**
 * {@link Token} subtype designed for WebWhois sequence.
 *
 * <p>Between loops of a WebWhois sequence the only thing changing is the tld we
 * are probing. As a result, we maintain the list of {@code topLevelDomains} and
 * on each call to next, have our index looking at the next {@code topLevelDomain}. </p>
 */
public class WebWhoisToken extends Token {

  /** For each top level domain (tld), we probe "prefix.tld". */
  private final String prefix;

  /** {@link ImmutableList} of all top level domains to be probed. */
  private final ImmutableList<String> topLevelDomains;

  /** Current index of {@code topLevelDomains} that represents tld we are probing. */
  private int domainsIndex;

  @Inject
  public WebWhoisToken(
      @Named("Web-WHOIS-Prefix") String prefix,
      @WebWhoisProtocol ImmutableList<String> topLevelDomains) {

    domainsIndex = 0;
    this.prefix = prefix;
    this.topLevelDomains = topLevelDomains;
  }

  /** Increments {@code domainsIndex} or resets it to reflect move to next top level domain. */
  @Override
  public WebWhoisToken next() {
    domainsIndex += 1;
    domainsIndex %= topLevelDomains.size();
    return this;
  }

  /** Modifies message to reflect the new host coming from the new top level domain. */
  @Override
  public OutboundMessageType modifyMessage(OutboundMessageType original) throws InternalException {
    return original.modifyMessage(getHost());
  }

  /** Returns host as the concatenation of fixed {@code prefix} and current value of {@code topLevelDomains}. */
  @Override
  public String getHost() {
    return prefix + topLevelDomains.get(domainsIndex);
  }
}

