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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.replicaTm;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.google.common.net.InetAddresses;
import com.google.common.net.InternetDomainName;
import google.registry.model.host.Host;
import google.registry.model.tld.Tlds;
import java.net.InetAddress;
import org.joda.time.DateTime;

/**
 * Represents a WHOIS lookup for a nameserver based on its IP.
 *
 * <p>Both IPv4 and IPv6 addresses are supported. Unlike other WHOIS commands, this is an eventually
 * consistent query.
 *
 * <p><b>Note:</b> There may be multiple nameservers with the same IP.
 */
final class NameserverLookupByIpCommand implements WhoisCommand {

  @VisibleForTesting
  final InetAddress ipAddress;

  NameserverLookupByIpCommand(InetAddress ipAddress) {
    this.ipAddress = checkNotNull(ipAddress, "ipAddress");
  }

  @Override
  @SuppressWarnings("unchecked")
  public WhoisResponse executeQuery(DateTime now) throws WhoisException {
    Iterable<Host> hostsFromDb;
    hostsFromDb =
        replicaTm()
            .transact(
                () ->
                    // We cannot query @Convert-ed fields in HQL, so we must use native Postgres.
                    replicaTm()
                        .getEntityManager()
                        /*
                         * Using array_operator <@ (contained-by) with gin index on inet_address.
                         * Without gin index, this is slightly slower than the alternative form of
                         * ':address = ANY(inet_address)'.
                         */
                        .createNativeQuery(
                            "SELECT * From \"Host\" WHERE "
                                + "ARRAY[ CAST(:address AS TEXT) ] <@ inet_addresses AND "
                                + "deletion_time > CAST(:now AS timestamptz)",
                            Host.class)
                        .setParameter("address", InetAddresses.toAddrString(ipAddress))
                        .setParameter("now", now.toString())
                        .getResultList());
    ImmutableList<Host> hosts =
        Streams.stream(hostsFromDb)
            .filter(
                host ->
                    Tlds.findTldForName(InternetDomainName.from(host.getHostName())).isPresent())
            .collect(toImmutableList());
    if (hosts.isEmpty()) {
      throw new WhoisException(now, SC_NOT_FOUND, "No nameservers found.");
    }
    return new NameserverWhoisResponse(hosts, now);
  }
}
