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

import static google.registry.model.EppResourceUtils.loadByForeignKeyCached;

import com.google.common.net.InternetDomainName;
import google.registry.model.host.HostResource;
import java.util.Optional;
import org.joda.time.DateTime;

/** Represents a WHOIS lookup on a nameserver based on its hostname. */
public class NameserverLookupByHostCommand extends DomainOrHostLookupCommand {

  NameserverLookupByHostCommand(InternetDomainName hostName) {
    super(hostName, "Nameserver");
  }

  @Override
  protected Optional<WhoisResponse> getResponse(InternetDomainName hostName, DateTime now) {
    return loadByForeignKeyCached(HostResource.class, hostName.toString(), now)
        .map(host -> new NameserverWhoisResponse(host, now));
  }
}
