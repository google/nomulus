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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.net.InternetDomainName;
import google.registry.dns.DnsConstants.TargetType;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import google.registry.model.common.DnsRefreshRequest;
import google.registry.model.tld.Registries;
import javax.inject.Inject;
import org.joda.time.Duration;

/** Utility class to handle DNS refresh requests. */
// TODO: Make this a static util function once we are done with the DNS pull queue migration.
public class DnsUtils {

  private final DnsQueue dnsQueue;

  @Inject
  DnsUtils(DnsQueue dnsQueue) {
    this.dnsQueue = dnsQueue;
  }

  private void requestDnsRefresh(String name, TargetType type, Duration delay) {
    // Throws an IllegalArgumentException if the name is not under a managed TLD -- we only update
    // DNS for names that are under our management.
    String tld = Registries.findTldForNameOrThrow(InternetDomainName.from(name)).toString();
    if (usePullQueue()) {
      if (TargetType.HOST.equals(type)) {
        dnsQueue.addHostRefreshTask(name);
      } else {
        dnsQueue.addDomainRefreshTask(name, delay);
      }
    } else {
      tm().transact(
              () ->
                  tm().insert(
                          new DnsRefreshRequest(
                              type, name, tld, tm().getTransactionTime().plus(delay))));
    }
  }

  public void requestDomainDnsRefresh(String domainName, Duration delay) {
    requestDnsRefresh(domainName, TargetType.DOMAIN, delay);
  }

  public void requestDomainDnsRefresh(String domainName) {
    requestDomainDnsRefresh(domainName, Duration.ZERO);
  }

  public void requestHostDnsRefresh(String hostName) {
    requestDnsRefresh(hostName, TargetType.HOST, Duration.ZERO);
  }

  private boolean usePullQueue() {
    return !DatabaseMigrationStateSchedule.getValueAtTime(dnsQueue.getClock().nowUtc())
        .equals(MigrationState.DNS_SQL);
  }
}
