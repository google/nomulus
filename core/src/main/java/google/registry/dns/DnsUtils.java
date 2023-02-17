// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.dns;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.net.InternetDomainName;
import google.registry.dns.DnsConstants.TargetType;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import google.registry.model.common.DnsRefreshRequest;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.model.tld.Registries;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.DateTime;

/** Utility class to handle DNS refresh requests. */
public class DnsUtils {

  private final DnsQueue dnsQueue;

  // TODO: Make this a static util class after the DNS pull queue migration.
  @Inject
  DnsUtils(DnsQueue dnsQueue) {
    this.dnsQueue = dnsQueue;
  }

  public void requestDnsRefresh(Domain domain, DateTime requestTime) {
    if (usePullQueue()) {
      dnsQueue.addDomainRefreshTask(domain.getDomainName());
    } else {
      tm().transact(
              () -> {
                tm().insert(
                        new DnsRefreshRequest(
                            TargetType.DOMAIN,
                            domain.getDomainName(),
                            domain.getTld(),
                            requestTime == null ? tm().getTransactionTime() : requestTime));
              });
    }
  }

  public void requestDnsRefresh(Domain domain) {
    requestDnsRefresh(domain, null);
  }

  public void requestDnsRefresh(Host host) {
    String hostName = host.getHostName();
    Optional<InternetDomainName> tld = Registries.findTldForName(InternetDomainName.from(hostName));
    checkArgument(
        tld.isPresent(), String.format("%s is not a subordinate host to a known tld", hostName));
    if (usePullQueue()) {
      dnsQueue.addHostRefreshTask(host.getHostName());
    } else {
      tm().transact(
              () -> {
                tm().insert(
                        new DnsRefreshRequest(
                            TargetType.HOST,
                            hostName,
                            tld.get().toString(),
                            tm().getTransactionTime()));
              });
    }
  }

  private boolean usePullQueue() {
    return !DatabaseMigrationStateSchedule.getValueAtTime(dnsQueue.clock.nowUtc())
        .equals(MigrationState.DNS_SQL);
  }
}
