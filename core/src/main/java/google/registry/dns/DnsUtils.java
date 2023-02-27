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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableList;
import com.google.common.net.InternetDomainName;
import google.registry.dns.DnsConstants.TargetType;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import google.registry.model.common.DnsRefreshRequest;
import google.registry.model.tld.Registries;
import java.util.Collection;
import javax.inject.Inject;
import org.joda.time.DateTime;
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

  /**
   * Returns pending DNS update requests that need further processing up to batch size, in ascending
   * order of their request time, and updates their processing time to now.
   *
   * <p>The criteria to pick the requests to include are:
   *
   * <ul>
   *   <li>They are for the given TLD.
   *   <li>Their request time is not in the future.
   *   <li>The last time they were processed is before the cooldown period.
   * </ul>
   */
  public ImmutableList<DnsRefreshRequest> readAndUpdateRequestsWithLatestProcessTime(
      String tld, Duration cooldown, int batchSize) {
    return tm().transact(
            () -> {
              DateTime transactionTime = tm().getTransactionTime();
              ImmutableList<DnsRefreshRequest> requests =
                  tm().query(
                          "FROM DnsRefreshRequest WHERE tld = :tld "
                              + "AND requestTime <= :now AND lastProcessTime < :cutoffTime "
                              + "ORDER BY requestTime ASC, id ASC",
                          DnsRefreshRequest.class)
                      .setParameter("tld", tld)
                      .setParameter("now", transactionTime)
                      .setParameter("cutoffTime", transactionTime.minus(cooldown))
                      .setMaxResults(batchSize)
                      .getResultStream()
                      // Note that the process time is when the request was last read, batched and
                      // queued up for publishing, not when it is actually published by the DNS
                      // writer. This timestamp acts as a cooldown so the same request will not be
                      // retried too frequently. See DnsRefreshRequest.getLastProcessTime for a
                      // detailed explaination.
                      .map(e -> e.updateProcessTime(transactionTime))
                      .collect(toImmutableList());
              tm().updateAll(requests);
              return requests;
            });
  }

  /**
   * Removes the requests that have been processed.
   *
   * <p>Note that if a request entity has already been deleted, the method still succeeds without
   * error because all we care about is that it no longer exists after the method runs.
   */
  public void deleteRequests(Collection<DnsRefreshRequest> requests) {
    tm().transact(
            () ->
                tm().delete(
                        requests.stream()
                            .map(DnsRefreshRequest::createVKey)
                            .collect(toImmutableList())));
  }

  private boolean usePullQueue() {
    return !DatabaseMigrationStateSchedule.getValueAtTime(dnsQueue.getClock().nowUtc())
        .equals(MigrationState.DNS_SQL);
  }
}
