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
import static com.google.common.base.Preconditions.checkState;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableList;
import com.google.common.net.InetAddresses;
import google.registry.model.host.HostResource;
import google.registry.model.registrar.Registrar;
import java.util.Optional;
import org.joda.time.DateTime;

/** Container for WHOIS responses to a nameserver lookup queries. */
final class NameserverWhoisResponse extends WhoisResponseImpl {

  /** Nameserver(s) which were the target of this WHOIS command. */
  private final ImmutableList<HostResource> hosts;

  /** Creates new WHOIS nameserver response on the given host. */
  NameserverWhoisResponse(HostResource host, DateTime timestamp) {
    this(ImmutableList.of(checkNotNull(host, "host")), timestamp);
  }

  /** Creates new WHOIS nameserver response on the given list of hosts. */
  NameserverWhoisResponse(ImmutableList<HostResource> hosts, DateTime timestamp) {
    super(timestamp);
    this.hosts = checkNotNull(hosts, "hosts");
  }

  @Override
  public WhoisResponseResults getResponse(boolean preferUnicode, String disclaimer) {
    BasicEmitter emitter = new BasicEmitter();
    for (int i = 0; i < hosts.size(); i++) {
      HostResource host = hosts.get(i);
      String clientId =
          host.isSubordinate()
              ? tm().loadByKey(host.getSuperordinateDomain())
                  .cloneProjectedAtTime(getTimestamp())
                  .getCurrentSponsorClientId()
              : host.getPersistedCurrentSponsorClientId();
      Optional<Registrar> registrar = Registrar.loadByClientIdCached(clientId);
      checkState(registrar.isPresent(), "Could not load registrar %s", clientId);
      emitter
          .emitField("Server Name", maybeFormatHostname(host.getHostName(), preferUnicode))
          .emitSet("IP Address", host.getInetAddresses(), InetAddresses::toAddrString)
          .emitField("Registrar", registrar.get().getRegistrarName())
          .emitField("Registrar WHOIS Server", registrar.get().getWhoisServer())
          .emitField("Registrar URL", registrar.get().getUrl());
      if (i < hosts.size() - 1) {
        emitter.emitNewline();
      }
    }
    String plaintext = emitter.emitLastUpdated(getTimestamp()).emitFooter(disclaimer).toString();
    return WhoisResponseResults.create(plaintext, hosts.size());
  }
}
