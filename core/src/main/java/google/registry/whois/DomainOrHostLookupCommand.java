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
import static google.registry.model.tld.Registries.findTldForName;
import static google.registry.model.tld.Registries.getTlds;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.InternetDomainName;
import java.util.Optional;
import org.joda.time.DateTime;

/** Represents a WHOIS lookup on a domain name (i.e. SLD) or a nameserver. */
public abstract class DomainOrHostLookupCommand implements WhoisCommand {

  @VisibleForTesting final InternetDomainName domainOrHostName;

  private final String errorPrefix;

  DomainOrHostLookupCommand(InternetDomainName domainName, String errorPrefix) {
    this.errorPrefix = errorPrefix;
    this.domainOrHostName = checkNotNull(domainName, "domainOrHostName");
  }

  @Override
  public final WhoisResponse executeQuery(final DateTime now) throws WhoisException {
    Optional<InternetDomainName> tld = findTldForName(domainOrHostName);
    // Google Registry Policy: Do not return records under TLDs for which we're not authoritative.
    if (tld.isPresent() && getTlds().contains(tld.get().toString())) {
      final Optional<WhoisResponse> response = getResponse(domainOrHostName, now);
      if (response.isPresent()) {
        return response.get();
      }
    }
    throw new WhoisException(now, SC_NOT_FOUND, errorPrefix + " not found.");
  }

  /** Renders a response record, provided its successfully retrieved Datastore entity. */
  protected abstract Optional<WhoisResponse> getResponse(
      InternetDomainName domainName, DateTime now);
}
