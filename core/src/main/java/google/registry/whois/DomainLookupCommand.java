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

import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.EppResourceUtils.loadByForeignKeyCached;
import static google.registry.model.tld.Tlds.findTldForName;
import static google.registry.model.tld.Tlds.getTlds;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.InternetDomainName;
import google.registry.flows.domain.DomainFlowUtils;
import google.registry.flows.domain.DomainFlowUtils.DomainLabelBlockedByBsaException;
import google.registry.model.domain.Domain;
import google.registry.model.tld.Tld;
import java.util.Optional;
import org.joda.time.DateTime;

/** Represents a WHOIS lookup on a domain name (i.e. SLD). */
public class DomainLookupCommand implements WhoisCommand {

  private static final String ERROR_PREFIX = "Domain";

  @VisibleForTesting final InternetDomainName domainName;

  private final boolean fullOutput;
  private final boolean cached;
  private final String whoisRedactedEmailText;
  private final String domainBlockedByBsaTemplate;

  public DomainLookupCommand(
      InternetDomainName domainName,
      boolean fullOutput,
      boolean cached,
      String whoisRedactedEmailText,
      String domainBlockedByBsaTemplate) {
    this.domainName = checkNotNull(domainName, "domainOrHostName");
    this.fullOutput = fullOutput;
    this.cached = cached;
    this.whoisRedactedEmailText = whoisRedactedEmailText;
    this.domainBlockedByBsaTemplate = domainBlockedByBsaTemplate;
  }

  @Override
  public final WhoisResponse executeQuery(final DateTime now) throws WhoisException {
    Optional<InternetDomainName> tld = findTldForName(domainName);
    // Google Registry Policy: Do not return records under TLDs for which we're not authoritative.
    if (tld.isPresent() && getTlds().contains(tld.get().toString())) {
      final Optional<WhoisResponse> response = getResponse(domainName, now);
      if (response.isPresent()) {
        return response.get();
      }
      Tld tldEntity = Tld.get(tld.get().toString());
      try {
        DomainFlowUtils.verifyNotBlockedByBsa(
            domainName.parts().get(0), Tld.get(tld.get().toString()), now);
      } catch (DomainLabelBlockedByBsaException e) {
        // "%domain-name% is defined in default-config.yaml
        throw new WhoisException(
            now, SC_NOT_FOUND, String.format(domainBlockedByBsaTemplate, domainName.toString()));
      }
    }
    throw new WhoisException(now, SC_NOT_FOUND, ERROR_PREFIX + " not found.");
  }

  private Optional<WhoisResponse> getResponse(InternetDomainName domainName, DateTime now) {
    Optional<Domain> domainResource =
        cached
            ? loadByForeignKeyCached(Domain.class, domainName.toString(), now)
            : loadByForeignKey(Domain.class, domainName.toString(), now);
    return domainResource.map(
        domain -> new DomainWhoisResponse(domain, fullOutput, whoisRedactedEmailText, now));
  }
}
