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
import static google.registry.model.tld.Tlds.findTldForName;
import static google.registry.model.tld.Tlds.getTlds;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.InternetDomainName;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.host.Host;
import java.util.Optional;
import org.joda.time.DateTime;

/** Represents a WHOIS lookup on a nameserver based on its hostname. */
public class NameserverLookupByHostCommand implements WhoisCommand {

  private static final String ERROR_PREFIX = "Nameserver";

  @VisibleForTesting final InternetDomainName hostName;

  boolean cached;

  NameserverLookupByHostCommand(InternetDomainName hostName, boolean cached) {
    this.hostName = checkNotNull(hostName, "hostName");
    this.cached = cached;
  }

  @Override
  public final WhoisResponse executeQuery(final DateTime now) throws WhoisException {
    Optional<InternetDomainName> tld = findTldForName(hostName);
    // Google Registry Policy: Do not return records under TLDs for which we're not authoritative.
    if (tld.isPresent() && getTlds().contains(tld.get().toString())) {
      final Optional<WhoisResponse> response = getResponse(hostName, now);
      if (response.isPresent()) {
        return response.get();
      }
    }
    throw new WhoisException(now, SC_NOT_FOUND, ERROR_PREFIX + " not found.");
  }

  private Optional<WhoisResponse> getResponse(InternetDomainName hostName, DateTime now) {
    Optional<Host> host =
        cached
            ? ForeignKeyUtils.loadResourceByCache(Host.class, hostName.toString(), now)
            : ForeignKeyUtils.loadResource(Host.class, hostName.toString(), now);
    return host.map(h -> new NameserverWhoisResponse(h, now));
  }
}
