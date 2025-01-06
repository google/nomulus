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

package google.registry.dns;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.PersistenceModule.TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.net.InternetDomainName;
import google.registry.model.common.DnsRefreshRequest;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tlds;
import java.util.Collection;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** Utility class to handle DNS refresh requests. */
public final class DnsUtils {

  /** The name of the DNS publish push queue. */
  public static final String DNS_PUBLISH_PUSH_QUEUE_NAME = "dns-publish"; // See queue.xml.

  private DnsUtils() {}

  private static void requestDnsRefresh(String name, TargetType type, Duration delay) {
    tm().assertInTransaction();
    // Throws an IllegalArgumentException if the name is not under a managed TLD -- we only update
    // DNS for names that are under our management.
    String tld = Tlds.findTldForNameOrThrow(InternetDomainName.from(name)).toString();
    tm().insert(new DnsRefreshRequest(type, name, tld, tm().getTransactionTime().plus(delay)));
  }

  private static void requestDnsRefresh(
      ImmutableCollection<String> names, TargetType type, Duration delay) {
    tm().assertInTransaction();
    DateTime requestTime = tm().getTransactionTime().plus(delay);
    tm().insertAll(
            names.stream()
                .map(
                    name ->
                        new DnsRefreshRequest(
                            type,
                            name,
                            Tlds.findTldForNameOrThrow(InternetDomainName.from(name)).toString(),
                            requestTime))
                .collect(toImmutableList()));
  }

  public static void requestDomainDnsRefresh(String domainName, Duration delay) {
    requestDnsRefresh(domainName, TargetType.DOMAIN, delay);
  }

  public static void requestDomainDnsRefresh(ImmutableCollection<String> names, Duration delay) {
    requestDnsRefresh(names, TargetType.DOMAIN, delay);
  }

  public static void requestDomainDnsRefresh(String domainName) {
    requestDomainDnsRefresh(domainName, Duration.ZERO);
  }

  public static void requestDomainDnsRefresh(ImmutableCollection<String> names) {
    requestDomainDnsRefresh(names, Duration.ZERO);
  }

  public static void requestHostDnsRefresh(String hostName) {
    requestDnsRefresh(hostName, TargetType.HOST, Duration.ZERO);
  }

  public static void requestHostDnsRefresh(ImmutableCollection<String> hostNames) {
    requestDnsRefresh(hostNames, TargetType.HOST, Duration.ZERO);
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
  public static ImmutableList<DnsRefreshRequest> readAndUpdateRequestsWithLatestProcessTime(
      String tld, Duration cooldown, int batchSize) {
    // It is critical that below query use repeatable-read. See b/337894387.
    return tm().transact(
            TRANSACTION_REPEATABLE_READ,
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
                      // detailed explanation.
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
  public static void deleteRequests(Collection<DnsRefreshRequest> requests) {
    // It is critical that below query use repeatable-read. See b/337894387.
    tm().transact(
            TRANSACTION_REPEATABLE_READ,
            () ->
                tm().delete(
                        requests.stream()
                            .map(DnsRefreshRequest::createVKey)
                            .collect(toImmutableList())));
  }

  public static long getDnsAPlusAAAATtlForHost(String host, Duration dnsDefaultATtl) {
    Optional<InternetDomainName> tldName = Tlds.findTldForName(InternetDomainName.from(host));
    Duration dnsAPlusAaaaTtl = dnsDefaultATtl;
    if (tldName.isPresent()) {
      Tld tld = Tld.get(tldName.get().toString());
      if (tld.getDnsAPlusAaaaTtl().isPresent()) {
        dnsAPlusAaaaTtl = tld.getDnsAPlusAaaaTtl().get();
      }
    }
    return dnsAPlusAaaaTtl.getStandardSeconds();
  }

  /** The possible values of the {@code DNS_TARGET_TYPE_PARAM} parameter. */
  public enum TargetType {
    DOMAIN,
    HOST
  }
}
