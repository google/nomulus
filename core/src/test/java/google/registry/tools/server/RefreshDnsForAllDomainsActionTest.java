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

package google.registry.tools.server;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.assertDnsRequestsWithRequestTime;
import static google.registry.testing.DatabaseHelper.assertDomainDnsRequestWithRequestTime;
import static google.registry.testing.DatabaseHelper.assertNoDnsRequestsExcept;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import java.util.Random;
import org.joda.time.DateTime;
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
    action = new RefreshDnsForAllDomainsAction();
    action.smearMinutes = 1;
    action.random = new Random();
    action.random.setSeed(123L);
    action.clock = clock;
    action.response = response;
    createTld("bar");
  }

  @Test
  void test_runAction_successfullyEnqueuesDnsRefreshes() throws Exception {
    persistActiveDomain("foo.bar");
    persistActiveDomain("low.bar");
    action.tlds = ImmutableSet.of("bar");
    action.run();
    assertDomainDnsRequestWithRequestTime("foo.bar", clock.nowUtc());
    assertDomainDnsRequestWithRequestTime("low.bar", clock.nowUtc());
  }

  @Test
  void test_runAction_smearsOutDnsRefreshes() throws Exception {
    persistActiveDomain("foo.bar");
    persistActiveDomain("low.bar");
    action.tlds = ImmutableSet.of("bar");
    action.smearMinutes = 1000;
    action.run();
    assertDomainDnsRequestWithRequestTime("foo.bar", clock.nowUtc().plusMinutes(782));
    assertDomainDnsRequestWithRequestTime("low.bar", clock.nowUtc().plusMinutes(450));
  }

  @Test
  void test_runAction_doesntRefreshDeletedDomain() throws Exception {
    persistActiveDomain("foo.bar");
    persistDeletedDomain("deleted.bar", clock.nowUtc().minusYears(1));
    action.tlds = ImmutableSet.of("bar");
    action.run();
    assertDomainDnsRequestWithRequestTime("foo.bar", clock.nowUtc());
    assertNoDnsRequestsExcept("foo.bar");
  }

  @Test
  void test_runAction_ignoresDomainsOnOtherTlds() throws Exception {
    createTld("baz");
    persistActiveDomain("foo.bar");
    persistActiveDomain("low.bar");
    persistActiveDomain("ignore.baz");
    action.tlds = ImmutableSet.of("bar");
    action.run();
    assertDomainDnsRequestWithRequestTime("foo.bar", clock.nowUtc());
    assertDomainDnsRequestWithRequestTime("low.bar", clock.nowUtc());
    assertNoDnsRequestsExcept("foo.bar", "low.bar");
  }

  @Test
  void test_smearMinutesMustBeSpecified() {
    action.tlds = ImmutableSet.of("bar");
    action.smearMinutes = 0;
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> action.run());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Must specify a positive number of smear minutes");
  }

  @Test
  void test_successfullyBatchesNames() {
    for (int i = 0; i <= RefreshDnsForAllDomainsAction.BATCH_SIZE; i++) {
      persistActiveDomain(String.format("test%s.bar", i));
    }
    action.tlds = ImmutableSet.of("bar");
    action.run();
    assertDnsRequestsWithRequestTime(clock.nowUtc(), RefreshDnsForAllDomainsAction.BATCH_SIZE + 1);
  }
}
