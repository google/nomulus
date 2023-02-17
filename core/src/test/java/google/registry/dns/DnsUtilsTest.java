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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import google.registry.dns.DnsConstants.TargetType;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import google.registry.model.common.DnsRefreshRequest;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link DnsUtils}. */
public class DnsUtilsTest {

  FakeClock clock = new FakeClock(DateTime.parse("2020-02-02T01:23:45Z"));

  private final DnsQueue dnsQueue = mock(DnsQueue.class);
  private final DnsUtils dnsUtils = new DnsUtils(dnsQueue);
  private final String tld = "tld";
  private final String domainName = "test.tld";
  private final String hostName = "ns1.test.tld";

  @RegisterExtension
  JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @BeforeAll
  static void beforeAll() {
    DatabaseMigrationStateSchedule.useUncachedForTest();
  }

  @BeforeEach
  void beforeEach() {
    createTld(tld);
    when(dnsQueue.getClock()).thenReturn(clock);
  }

  @Test
  void testSuccess_hostRefresh_pullQueue() {
    dnsUtils.requestHostDnsRefresh(hostName);
    verify(dnsQueue).addHostRefreshTask(hostName);
    assertThat(loadAllOf(DnsRefreshRequest.class)).isEmpty();
  }

  @Test
  void testSuccess_domainRefresh_pullQueue() {
    dnsUtils.requestDomainDnsRefresh(domainName);
    verify(dnsQueue).addDomainRefreshTask(domainName, Duration.ZERO);
    assertThat(loadAllOf(DnsRefreshRequest.class)).isEmpty();
  }

  @Test
  void testSuccess_domainRefreshWithDelay_pullQueue() {
    dnsUtils.requestDomainDnsRefresh(domainName, Duration.standardMinutes(3));
    verify(dnsQueue).addDomainRefreshTask(domainName, Duration.standardMinutes(3));
    assertThat(loadAllOf(DnsRefreshRequest.class)).isEmpty();
  }

  @Test
  void testFailure_hostRefresh_nonSubordinateHost() {
    String nonSubordinateHostName = "ns1.another.example";
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> dnsUtils.requestHostDnsRefresh(nonSubordinateHostName));
    verify(dnsQueue, times(0)).addHostRefreshTask(anyString());
    assertThat(loadAllOf(DnsRefreshRequest.class)).isEmpty();
  }

  @Test
  void testFailure_domainRefresh_nonSubordinateDomain() {
    String nonSubordinateDomainName = "another.example";
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> dnsUtils.requestDomainDnsRefresh(nonSubordinateDomainName));
    verify(dnsQueue, times(0)).addDomainRefreshTask(anyString(), any(Duration.class));
    assertThat(loadAllOf(DnsRefreshRequest.class)).isEmpty();
  }

  @Test
  void testSuccess_hostRefresh() {
    useDnsSql();
    dnsUtils.requestHostDnsRefresh(hostName);
    verify(dnsQueue, times(0)).addHostRefreshTask(anyString());
    DnsRefreshRequest request = Iterables.getOnlyElement(loadAllOf(DnsRefreshRequest.class));
    assertRequest(request, TargetType.HOST, hostName, tld, clock.nowUtc());
  }

  @Test
  void testSuccess_domainRefresh() {
    useDnsSql();
    dnsUtils.requestDomainDnsRefresh(domainName);
    verify(dnsQueue, times(0)).addDomainRefreshTask(anyString(), any(Duration.class));
    DnsRefreshRequest request = Iterables.getOnlyElement(loadAllOf(DnsRefreshRequest.class));
    assertRequest(request, TargetType.DOMAIN, domainName, tld, clock.nowUtc());
  }

  @Test
  void testSuccess_domainRefreshwithDelay() {
    useDnsSql();
    dnsUtils.requestDomainDnsRefresh(domainName, Duration.standardMinutes(3));
    verify(dnsQueue, times(0)).addDomainRefreshTask(anyString(), any(Duration.class));
    DnsRefreshRequest request = Iterables.getOnlyElement(loadAllOf(DnsRefreshRequest.class));
    assertRequest(request, TargetType.DOMAIN, domainName, tld, clock.nowUtc().plusMinutes(3));
  }

  private static void assertRequest(
      DnsRefreshRequest request, TargetType type, String name, String tld, DateTime requestTime) {
    assertThat(request.getType()).isEqualTo(type);
    assertThat(request.getName()).isEqualTo(name);
    assertThat(request.getTld()).isEqualTo(tld);
    assertThat(request.getRequestTime()).isEqualTo(requestTime);
  }

  private static void useDnsSql() {
    tm().transact(
            () ->
                DatabaseMigrationStateSchedule.set(
                    new ImmutableSortedMap.Builder<DateTime, MigrationState>(Ordering.natural())
                        .put(START_OF_TIME, MigrationState.DATASTORE_ONLY)
                        .put(START_OF_TIME.plusMillis(1), MigrationState.DATASTORE_PRIMARY)
                        .build()));
    tm().transact(
            () ->
                DatabaseMigrationStateSchedule.set(
                    new ImmutableSortedMap.Builder<DateTime, MigrationState>(Ordering.natural())
                        .put(START_OF_TIME, MigrationState.DATASTORE_ONLY)
                        .put(START_OF_TIME.plusMillis(1), MigrationState.DATASTORE_PRIMARY)
                        .put(START_OF_TIME.plusMillis(2), MigrationState.DATASTORE_PRIMARY_NO_ASYNC)
                        .build()));
    tm().transact(
            () ->
                DatabaseMigrationStateSchedule.set(
                    new ImmutableSortedMap.Builder<DateTime, MigrationState>(Ordering.natural())
                        .put(START_OF_TIME, MigrationState.DATASTORE_ONLY)
                        .put(START_OF_TIME.plusMillis(1), MigrationState.DATASTORE_PRIMARY)
                        .put(START_OF_TIME.plusMillis(2), MigrationState.DATASTORE_PRIMARY_NO_ASYNC)
                        .put(
                            START_OF_TIME.plusMillis(3), MigrationState.DATASTORE_PRIMARY_READ_ONLY)
                        .build()));
    tm().transact(
            () ->
                DatabaseMigrationStateSchedule.set(
                    new ImmutableSortedMap.Builder<DateTime, MigrationState>(Ordering.natural())
                        .put(START_OF_TIME, MigrationState.DATASTORE_ONLY)
                        .put(START_OF_TIME.plusMillis(1), MigrationState.DATASTORE_PRIMARY)
                        .put(START_OF_TIME.plusMillis(2), MigrationState.DATASTORE_PRIMARY_NO_ASYNC)
                        .put(
                            START_OF_TIME.plusMillis(3), MigrationState.DATASTORE_PRIMARY_READ_ONLY)
                        .put(START_OF_TIME.plusMillis(4), MigrationState.SQL_PRIMARY_READ_ONLY)
                        .build()));
    tm().transact(
            () ->
                DatabaseMigrationStateSchedule.set(
                    new ImmutableSortedMap.Builder<DateTime, MigrationState>(Ordering.natural())
                        .put(START_OF_TIME, MigrationState.DATASTORE_ONLY)
                        .put(START_OF_TIME.plusMillis(1), MigrationState.DATASTORE_PRIMARY)
                        .put(START_OF_TIME.plusMillis(2), MigrationState.DATASTORE_PRIMARY_NO_ASYNC)
                        .put(
                            START_OF_TIME.plusMillis(3), MigrationState.DATASTORE_PRIMARY_READ_ONLY)
                        .put(START_OF_TIME.plusMillis(4), MigrationState.SQL_PRIMARY_READ_ONLY)
                        .put(START_OF_TIME.plusMillis(5), MigrationState.SQL_PRIMARY)
                        .build()));
    tm().transact(
            () ->
                DatabaseMigrationStateSchedule.set(
                    new ImmutableSortedMap.Builder<DateTime, MigrationState>(Ordering.natural())
                        .put(START_OF_TIME, MigrationState.DATASTORE_ONLY)
                        .put(START_OF_TIME.plusMillis(1), MigrationState.DATASTORE_PRIMARY)
                        .put(START_OF_TIME.plusMillis(2), MigrationState.DATASTORE_PRIMARY_NO_ASYNC)
                        .put(
                            START_OF_TIME.plusMillis(3), MigrationState.DATASTORE_PRIMARY_READ_ONLY)
                        .put(START_OF_TIME.plusMillis(4), MigrationState.SQL_PRIMARY_READ_ONLY)
                        .put(START_OF_TIME.plusMillis(5), MigrationState.SQL_PRIMARY)
                        .put(START_OF_TIME.plusMillis(6), MigrationState.SQL_ONLY)
                        .build()));
    tm().transact(
            () ->
                DatabaseMigrationStateSchedule.set(
                    new ImmutableSortedMap.Builder<DateTime, MigrationState>(Ordering.natural())
                        .put(START_OF_TIME, MigrationState.DATASTORE_ONLY)
                        .put(START_OF_TIME.plusMillis(1), MigrationState.DATASTORE_PRIMARY)
                        .put(START_OF_TIME.plusMillis(2), MigrationState.DATASTORE_PRIMARY_NO_ASYNC)
                        .put(
                            START_OF_TIME.plusMillis(3), MigrationState.DATASTORE_PRIMARY_READ_ONLY)
                        .put(START_OF_TIME.plusMillis(4), MigrationState.SQL_PRIMARY_READ_ONLY)
                        .put(START_OF_TIME.plusMillis(5), MigrationState.SQL_PRIMARY)
                        .put(START_OF_TIME.plusMillis(6), MigrationState.SQL_ONLY)
                        .put(START_OF_TIME.plusMillis(7), MigrationState.SEQUENCE_BASED_ALLOCATE_ID)
                        .build()));
    tm().transact(
            () ->
                DatabaseMigrationStateSchedule.set(
                    new ImmutableSortedMap.Builder<DateTime, MigrationState>(Ordering.natural())
                        .put(START_OF_TIME, MigrationState.DATASTORE_ONLY)
                        .put(START_OF_TIME.plusMillis(1), MigrationState.DATASTORE_PRIMARY)
                        .put(START_OF_TIME.plusMillis(2), MigrationState.DATASTORE_PRIMARY_NO_ASYNC)
                        .put(
                            START_OF_TIME.plusMillis(3), MigrationState.DATASTORE_PRIMARY_READ_ONLY)
                        .put(START_OF_TIME.plusMillis(4), MigrationState.SQL_PRIMARY_READ_ONLY)
                        .put(START_OF_TIME.plusMillis(5), MigrationState.SQL_PRIMARY)
                        .put(START_OF_TIME.plusMillis(6), MigrationState.SQL_ONLY)
                        .put(START_OF_TIME.plusMillis(7), MigrationState.SEQUENCE_BASED_ALLOCATE_ID)
                        .put(START_OF_TIME.plusMillis(8), MigrationState.NORDN_SQL)
                        .build()));
    tm().transact(
            () ->
                DatabaseMigrationStateSchedule.set(
                    new ImmutableSortedMap.Builder<DateTime, MigrationState>(Ordering.natural())
                        .put(START_OF_TIME, MigrationState.DATASTORE_ONLY)
                        .put(START_OF_TIME.plusMillis(1), MigrationState.DATASTORE_PRIMARY)
                        .put(START_OF_TIME.plusMillis(2), MigrationState.DATASTORE_PRIMARY_NO_ASYNC)
                        .put(
                            START_OF_TIME.plusMillis(3), MigrationState.DATASTORE_PRIMARY_READ_ONLY)
                        .put(START_OF_TIME.plusMillis(4), MigrationState.SQL_PRIMARY_READ_ONLY)
                        .put(START_OF_TIME.plusMillis(5), MigrationState.SQL_PRIMARY)
                        .put(START_OF_TIME.plusMillis(6), MigrationState.SQL_ONLY)
                        .put(START_OF_TIME.plusMillis(7), MigrationState.SEQUENCE_BASED_ALLOCATE_ID)
                        .put(START_OF_TIME.plusMillis(8), MigrationState.NORDN_SQL)
                        .put(START_OF_TIME.plusMillis(9), MigrationState.DNS_SQL)
                        .build()));
  }
}
