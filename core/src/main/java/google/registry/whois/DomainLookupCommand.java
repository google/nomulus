// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.flows.domain.DomainFlowUtils.isBlockedByBsa;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.EppResourceUtils.loadByForeignKeyCached;
import static google.registry.model.tld.Tlds.findTldForName;
import static google.registry.model.tld.Tlds.getTlds;
import static google.registry.persistence.transaction.TransactionManagerFactory.replicaTm;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Verify;
import com.google.common.net.InternetDomainName;
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
    // Google Registry Policy: Do not return records under TLDs for which we're not
    // authoritative.
    if (tld.isEmpty() || !getTlds().contains(tld.get().toString())) {
      throw new WhoisException(now, SC_NOT_FOUND, ERROR_PREFIX + " not found.");
    }
    // Include `getResponse` and `isBlockedByBsa` in one transaction to reduce latency.
    // Must pass the exceptions outside to throw.
    ResponseOrException result =
        replicaTm()
            .transact(
                () -> {
                  final Optional<WhoisResponse> response = getResponse(domainName, now);
                  if (response.isPresent()) {
                    return ResponseOrException.of(response.get());
                  }

                  String label = domainName.parts().get(0);
                  String tldStr = tld.get().toString();
                  if (isBlockedByBsa(label, Tld.get(tldStr), now)) {
                    return ResponseOrException.of(
                        new WhoisException(
                            now,
                            SC_NOT_FOUND,
                            String.format(domainBlockedByBsaTemplate, domainName)));
                  }

                  return ResponseOrException.of(
                      new WhoisException(now, SC_NOT_FOUND, ERROR_PREFIX + " not found."));
                });
    return result.returnOrThrow();
  }

  private Optional<WhoisResponse> getResponse(InternetDomainName domainName, DateTime now) {
    Optional<Domain> domainResource =
        cached
            ? loadByForeignKeyCached(Domain.class, domainName.toString(), now)
            : loadByForeignKey(Domain.class, domainName.toString(), now);
    return domainResource.map(
        domain -> new DomainWhoisResponse(domain, fullOutput, whoisRedactedEmailText, now));
  }

  record ResponseOrException(
      Optional<WhoisResponse> whoisResponse, Optional<WhoisException> exception) {

    WhoisResponse returnOrThrow() throws WhoisException {
      Verify.verify(
          whoisResponse().isPresent() || exception().isPresent(),
          "Response and exception must not both be missing.");
      return whoisResponse().orElseThrow(() -> exception().get());
    }

    static ResponseOrException of(WhoisResponse response) {
      return new ResponseOrException(Optional.of(response), Optional.empty());
    }

    static ResponseOrException of(WhoisException exception) {
      return new ResponseOrException(Optional.empty(), Optional.of(exception));
    }
  }
}
