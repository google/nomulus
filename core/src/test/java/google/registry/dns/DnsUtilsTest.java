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
import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import google.registry.dns.DnsUtils.TargetType;
import google.registry.model.common.DnsRefreshRequest;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import java.util.Comparator;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link DnsUtils}. */
public class DnsUtilsTest {

  private static final String tld = "tld";
  private static final String domainName = "test.tld";
  private static final String hostName = "ns1.test.tld";

  private final FakeClock clock = new FakeClock(DateTime.parse("2020-02-02T01:23:45Z"));

  @RegisterExtension
  JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    createTld(tld);
  }

  @Test
  void testFailure_hostRefresh_unmanagedHost() {
    String unmanagedHostName = "ns1.another.example";
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> DnsUtils.requestHostDnsRefresh(unmanagedHostName));
    assertThat(loadAllOf(DnsRefreshRequest.class)).isEmpty();
  }

  @Test
  void testFailure_domainRefresh_unmanagedDomain() {
    String unmanagedDomainName = "another.example";
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> DnsUtils.requestDomainDnsRefresh(unmanagedDomainName));
    assertThat(loadAllOf(DnsRefreshRequest.class)).isEmpty();
  }

  @Test
  void testSuccess_hostRefresh() {
    DnsUtils.requestHostDnsRefresh(hostName);
    DnsRefreshRequest request = Iterables.getOnlyElement(loadAllOf(DnsRefreshRequest.class));
    assertRequest(request, DnsUtils.TargetType.HOST, hostName, tld, clock.nowUtc());
  }

  @Test
  void testSuccess_domainRefresh() {
    DnsUtils.requestDomainDnsRefresh(domainName);
    DnsRefreshRequest request = Iterables.getOnlyElement(loadAllOf(DnsRefreshRequest.class));
    assertRequest(request, DnsUtils.TargetType.DOMAIN, domainName, tld, clock.nowUtc());
  }

  @Test
  void testSuccess_domainRefreshWithDelay() {
    DnsUtils.requestDomainDnsRefresh(domainName, Duration.standardMinutes(3));
    DnsRefreshRequest request = Iterables.getOnlyElement(loadAllOf(DnsRefreshRequest.class));
    assertRequest(
        request, DnsUtils.TargetType.DOMAIN, domainName, tld, clock.nowUtc().plusMinutes(3));
  }

  @Test
  void testSuccess_ProcessRequests() {
    ImmutableList<DnsRefreshRequest> requests = processRequests();
    DateTime processtime = clock.nowUtc();
    assertThat(requests.size()).isEqualTo(4);
    assertRequest(
        requests.get(0),
        DnsUtils.TargetType.DOMAIN,
        "test2.tld",
        "tld",
        clock.nowUtc().minusMinutes(4),
        processtime);
    assertRequest(
        requests.get(1),
        DnsUtils.TargetType.DOMAIN,
        "test1.tld",
        "tld",
        clock.nowUtc().minusMinutes(3),
        processtime);
    assertRequest(
        requests.get(2),
        DnsUtils.TargetType.HOST,
        "ns1.test2.tld",
        "tld",
        clock.nowUtc().minusMinutes(1),
        processtime);
    assertRequest(
        requests.get(3),
        DnsUtils.TargetType.DOMAIN,
        "test5.tld",
        "tld",
        clock.nowUtc().minusMinutes(1),
        processtime);
    requests = loadAllOf(DnsRefreshRequest.class);
    assertThat(requests.size()).isEqualTo(7);
    // The four processed records should have updated process time in SQL as well.
    assertThat(requests.stream().filter(e -> e.getLastProcessTime().equals(processtime)).count())
        .isEqualTo(4);
    clock.advanceOneMilli();

    // Requests within cooldown period not included.
    requests =
        DnsUtils.readAndUpdateRequestsWithLatestProcessTime("tld", Duration.standardMinutes(1), 4);
    assertThat(requests.size()).isEqualTo(1);
    assertRequest(
        requests.get(0),
        DnsUtils.TargetType.DOMAIN,
        "test6.tld",
        "tld",
        clock.nowUtc().minusMinutes(1).minusMillis(1),
        clock.nowUtc());
  }

  @Test
  void testSuccess_deleteRequests() {
    DnsUtils.deleteRequests(processRequests());
    ImmutableList<DnsRefreshRequest> remainingRequests =
        loadAllOf(DnsRefreshRequest.class).stream()
            .sorted(Comparator.comparing(DnsRefreshRequest::getRequestTime))
            .collect(toImmutableList());
    assertThat(remainingRequests.size()).isEqualTo(3);
    assertRequest(
        remainingRequests.get(0),
        DnsUtils.TargetType.DOMAIN,
        "something.example",
        "example",
        clock.nowUtc().minusMinutes(2));
    assertRequest(
        remainingRequests.get(1),
        DnsUtils.TargetType.DOMAIN,
        "test6.tld",
        "tld",
        clock.nowUtc().minusMinutes(1));
    assertRequest(
        remainingRequests.get(2),
        DnsUtils.TargetType.DOMAIN,
        "test4.tld",
        "tld",
        clock.nowUtc().plusMinutes(1));
    tm().transact(() -> tm().delete(remainingRequests.get(2)));
    assertThat(loadAllOf(DnsRefreshRequest.class).size()).isEqualTo(2);
    // Should not throw even though one of the request is already deleted.
    DnsUtils.deleteRequests(remainingRequests);
    assertThat(loadAllOf(DnsRefreshRequest.class).size()).isEqualTo(0);
  }

  private ImmutableList<DnsRefreshRequest> processRequests() {
    createTld("example");
    // Domain Included.
    DnsUtils.requestDomainDnsRefresh("test1.tld", Duration.standardMinutes(1));
    // This one should be returned before test1.tld, even though it's added later, because of
    // the delay specified in test1.tld.
    DnsUtils.requestDomainDnsRefresh("test2.tld");
    // Not included because the TLD is not under management.
    DnsUtils.requestDomainDnsRefresh("something.example", Duration.standardMinutes(2));
    clock.advanceBy(Duration.standardMinutes(3));
    // Host included.
    DnsUtils.requestHostDnsRefresh("ns1.test2.tld");
    // Not included because the request time is in the future
    DnsUtils.requestDomainDnsRefresh("test4.tld", Duration.standardMinutes(2));
    // Included after the previous one. Same request time, order by insertion order (i.e. ID);
    DnsUtils.requestDomainDnsRefresh("test5.tld");
    // Not included because batch size is exceeded;
    DnsUtils.requestDomainDnsRefresh("test6.tld");
    clock.advanceBy(Duration.standardMinutes(1));
    return DnsUtils.readAndUpdateRequestsWithLatestProcessTime(
        "tld", Duration.standardMinutes(1), 4);
  }

  private static void assertRequest(
      DnsRefreshRequest request, TargetType type, String name, String tld, DateTime requestTime) {
    assertRequest(request, type, name, tld, requestTime, START_OF_TIME);
  }

  private static void assertRequest(
      DnsRefreshRequest request,
      TargetType type,
      String name,
      String tld,
      DateTime requestTime,
      DateTime processTime) {
    assertThat(request.getType()).isEqualTo(type);
    assertThat(request.getName()).isEqualTo(name);
    assertThat(request.getTld()).isEqualTo(tld);
    assertThat(request.getRequestTime()).isEqualTo(requestTime);
    assertThat(request.getLastProcessTime()).isEqualTo(processTime);
  }
}
