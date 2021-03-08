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

package google.registry.whois;

import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.EppResourceUtils.loadByForeignKeyCached;

import com.google.common.net.InternetDomainName;
import google.registry.model.domain.DomainBase;
import java.util.Optional;
import org.joda.time.DateTime;

/** Represents a WHOIS lookup on a domain name (i.e. SLD). */
public class DomainLookupCommand extends DomainOrHostLookupCommand {

  private final boolean fullOutput;
  private final boolean cached;
  private final String whoisRedactedEmailText;

  public DomainLookupCommand(
      InternetDomainName domainName,
      boolean fullOutput,
      boolean cached,
      String whoisRedactedEmailText) {
    super(domainName, "Domain");
    this.fullOutput = fullOutput;
    this.cached = cached;
    this.whoisRedactedEmailText = whoisRedactedEmailText;
  }

  @Override
  protected Optional<WhoisResponse> getResponse(InternetDomainName domainName, DateTime now) {
    Optional<DomainBase> domainResource =
        cached
            ? loadByForeignKeyCached(DomainBase.class, domainName.toString(), now)
            : loadByForeignKey(DomainBase.class, domainName.toString(), now);
    return domainResource.map(
        domain -> new DomainWhoisResponse(domain, fullOutput, whoisRedactedEmailText, now));
  }
}
