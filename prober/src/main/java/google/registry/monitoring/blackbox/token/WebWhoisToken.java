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

package google.registry.monitoring.blackbox.token;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;
import google.registry.monitoring.blackbox.exception.UndeterminedStateException;
import google.registry.monitoring.blackbox.message.OutboundMessageType;
import google.registry.monitoring.blackbox.module.WebWhoisModule.WebWhoisProtocol;
import javax.inject.Inject;

/**
 * {@link Token} subtype designed for WebWhois sequence.
 *
 * <p>Between loops of a WebWhois sequence the only thing changing is the tld we are probing. As a
 * result, we maintain the list of {@code topLevelDomains} and on each call to next, have our index
 * looking at the next {@code topLevelDomain}.
 */
public class WebWhoisToken extends Token {

  /** For each top level domain (tld), we probe "prefix.tld". */
  private static final String PREFIX = "whois.nic.";

  /** {@link PeekingIterator} over a cycle of all top level domains to be probed. */
  private final PeekingIterator<String> topLevelDomainsList;

  @Inject
  public WebWhoisToken(@WebWhoisProtocol ImmutableList<String> topLevelDomainsList) {
    checkArgument(!topLevelDomainsList.isEmpty(), "topLevelDomainsList must not be empty.");

    this.topLevelDomainsList =
        Iterators.peekingIterator(Iterables.cycle(topLevelDomainsList).iterator());
  }

  /** Moves on to next top level domain in {@code topLevelDomainsList}. */
  @Override
  public WebWhoisToken next() {
    topLevelDomainsList.next();
    return this;
  }

  /** Modifies message to reflect the new host coming from the new top level domain. */
  @Override
  public OutboundMessageType modifyMessage(OutboundMessageType original)
      throws UndeterminedStateException {
    return original.modifyMessage(host());
  }

  /**
   * Returns host as the concatenation of fixed {@code prefix} and current value of {@code
   * topLevelDomains}.
   */
  @Override
  public String host() {
    return PREFIX + topLevelDomainsList.peek();
  }
}
