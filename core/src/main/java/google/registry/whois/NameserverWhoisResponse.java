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
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.replicaTm;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.net.InetAddresses;
import google.registry.model.host.Host;
import google.registry.model.registrar.Registrar;
import java.util.Optional;
import org.joda.time.DateTime;

/** Container for WHOIS responses to a nameserver lookup queries. */
final class NameserverWhoisResponse extends WhoisResponseImpl {

  /** Nameserver(s) which were the target of this WHOIS command. */
  private final ImmutableList<Host> hosts;

  /** Creates new WHOIS nameserver response on the given host. */
  NameserverWhoisResponse(Host host, DateTime timestamp) {
    this(ImmutableList.of(checkNotNull(host, "host")), timestamp);
  }

  /** Creates new WHOIS nameserver response on the given list of hosts. */
  NameserverWhoisResponse(ImmutableList<Host> hosts, DateTime timestamp) {
    super(timestamp);
    this.hosts = checkNotNull(hosts, "hosts");
  }

  @Override
  public WhoisResponseResults getResponse(boolean preferUnicode, String disclaimer) {
    // If we have subordinate hosts, load their registrar ids in a single transaction up-front.
    ImmutableList<Host> subordinateHosts =
        hosts.stream().filter(Host::isSubordinate).collect(toImmutableList());
    ImmutableMap<Host, String> hostRegistrars =
        subordinateHosts.isEmpty()
            ? ImmutableMap.of()
            : replicaTm()
                .transact(
                    () ->
                        Maps.toMap(
                            subordinateHosts.iterator(),
                            host ->
                                replicaTm()
                                    .loadByKey(host.getSuperordinateDomain())
                                    .cloneProjectedAtTime(getTimestamp())
                                    .getCurrentSponsorRegistrarId()));

    BasicEmitter emitter = new BasicEmitter();
    for (int i = 0; i < hosts.size(); i++) {
      Host host = hosts.get(i);
      String registrarId =
          host.isSubordinate()
              ? hostRegistrars.get(host)
              : host.getPersistedCurrentSponsorRegistrarId();
      Optional<Registrar> registrar = Registrar.loadByRegistrarIdCached(registrarId);
      checkState(registrar.isPresent(), "Could not load registrar %s", registrarId);
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
