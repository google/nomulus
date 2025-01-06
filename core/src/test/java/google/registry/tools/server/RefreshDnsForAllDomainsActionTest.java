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

package google.registry.tools.server;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.QueryComposer.Comparator.EQ;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.assertDnsRequestsWithRequestTime;
import static google.registry.testing.DatabaseHelper.assertDomainDnsRequestWithRequestTime;
import static google.registry.testing.DatabaseHelper.assertNoDnsRequestsExcept;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.dns.DnsUtils;
import google.registry.model.common.DnsRefreshRequest;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import java.util.Optional;
import java.util.Random;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RefreshDnsForAllDomainsAction}. */
public class RefreshDnsForAllDomainsActionTest {

  private final FakeClock clock = new FakeClock(DateTime.parse("2020-02-02T02:02:02Z"));
  private RefreshDnsForAllDomainsAction action;
  private final FakeResponse response = new FakeResponse();

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    createTld("bar");
    action =
        new RefreshDnsForAllDomainsAction(
            response,
            ImmutableSet.of("bar"),
            Optional.of(10),
            Optional.empty(),
            Optional.empty(),
            new Random());
  }

  @Test
  void test_runAction_successfullyEnqueuesDnsRefreshes() throws Exception {
    persistActiveDomain("foo.bar");
    persistActiveDomain("low.bar");
    action.run();
    assertDomainDnsRequestWithRequestTime("foo.bar", clock.nowUtc());
    assertDomainDnsRequestWithRequestTime("low.bar", clock.nowUtc());
  }

  @Test
  void test_runAction_smearsOutDnsRefreshes() throws Exception {
    persistActiveDomain("foo.bar");
    persistActiveDomain("low.bar");
    // Set batch size to 1 since each batch will be enqueud at the same time
    action =
        new RefreshDnsForAllDomainsAction(
            response,
            ImmutableSet.of("bar"),
            Optional.of(1),
            Optional.of(7),
            Optional.empty(),
            new Random());
    tm().transact(() -> action.refreshBatch(Optional.empty(), Duration.standardMinutes(1000)));
    tm().transact(() -> action.refreshBatch(Optional.empty(), Duration.standardMinutes(1000)));
    ImmutableList<DnsRefreshRequest> refreshRequests =
        tm().transact(
                () ->
                    tm().createQueryComposer(DnsRefreshRequest.class)
                        .where("type", EQ, DnsUtils.TargetType.DOMAIN)
                        .list());
    assertThat(refreshRequests.size()).isEqualTo(2);
    assertThat(refreshRequests.get(0).getRequestTime())
        .isNotEqualTo(refreshRequests.get(1).getRequestTime());
  }

  @Test
  void test_runAction_doesntRefreshDeletedDomain() throws Exception {
    persistActiveDomain("foo.bar");
    persistDeletedDomain("deleted.bar", clock.nowUtc().minusYears(1));
    action.run();
    assertDomainDnsRequestWithRequestTime("foo.bar", clock.nowUtc());
    assertNoDnsRequestsExcept("foo.bar");
  }

  @Test
  void test_runAction_refreshesDeletedDomain_whenActiveOrDeletedSinceIsProvided() throws Exception {
    action =
        new RefreshDnsForAllDomainsAction(
            response,
            ImmutableSet.of("bar"),
            Optional.of(1),
            Optional.of(7),
            Optional.of(clock.nowUtc().minusYears(3)),
            new Random());
    persistActiveDomain("foo.bar");
    persistDeletedDomain("deleted1.bar", clock.nowUtc().minusYears(1));
    persistDeletedDomain("deleted3.bar", clock.nowUtc().minusYears(3));
    persistDeletedDomain("deleted5.bar", clock.nowUtc().minusYears(5));
    action.run();
    assertDomainDnsRequestWithRequestTime("foo.bar", clock.nowUtc());
    assertDomainDnsRequestWithRequestTime("deleted1.bar", clock.nowUtc());
    assertDomainDnsRequestWithRequestTime("deleted3.bar", clock.nowUtc());

    assertNoDnsRequestsExcept("foo.bar", "deleted1.bar", "deleted3.bar");
  }

  @Test
  void test_runAction_ignoresDomainsOnOtherTlds() throws Exception {
    createTld("baz");
    persistActiveDomain("foo.bar");
    persistActiveDomain("low.bar");
    persistActiveDomain("ignore.baz");
    action.run();
    assertDomainDnsRequestWithRequestTime("foo.bar", clock.nowUtc());
    assertDomainDnsRequestWithRequestTime("low.bar", clock.nowUtc());
    assertNoDnsRequestsExcept("foo.bar", "low.bar");
  }

  @Test
  void test_successfullyBatchesNames() {
    for (int i = 0; i <= 10; i++) {
      persistActiveDomain(String.format("test%s.bar", i));
    }
    action.run();
    assertDnsRequestsWithRequestTime(clock.nowUtc(), 11);
  }
}
