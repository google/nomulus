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
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.collect.ImmutableSet;
import google.registry.dns.DnsUtils;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.DnsUtilsHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import java.util.Random;
import org.apache.http.HttpStatus;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RefreshDnsForAllDomainsAction}. */
public class RefreshDnsForAllDomainsActionTest {

  private final FakeClock clock = new FakeClock(DateTime.parse("2020-02-02T02:02:02Z"));
  private final DnsUtils dnsUtils = mock(DnsUtils.class);
  private final DnsUtilsHelper dnsUtilsHelper = new DnsUtilsHelper(dnsUtils);
  private RefreshDnsForAllDomainsAction action;
  private final FakeResponse response = new FakeResponse();

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    action = new RefreshDnsForAllDomainsAction();
    action.smearMinutes = 1;
    action.random = new Random();
    action.random.setSeed(123L);
    action.clock = clock;
    action.dnsUtils = dnsUtils;
    action.response = response;

    createTld("bar");
  }

  @Test
  void test_runAction_errorRequestDnsRefresh() throws Exception {
    persistActiveDomain("foo.bar");
    persistActiveDomain("baz.bar");
    persistActiveDomain("low.bar");
    action.tlds = ImmutableSet.of("bar");
    doThrow(new RuntimeException("Error enqueuing task."))
        .when(dnsUtils)
        .requestDomainDnsRefresh(eq("baz.bar"), any(Duration.class));
    action.run();
    dnsUtilsHelper.assertDomainDnsRequestWithDelay("low.bar", Duration.ZERO);
    dnsUtilsHelper.assertDomainDnsRequestWithDelay("baz.bar", Duration.ZERO);
    dnsUtilsHelper.assertDomainDnsRequestWithDelay("foo.bar", Duration.ZERO);
    verifyNoMoreInteractions(dnsUtils);
    assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_INTERNAL_SERVER_ERROR);
  }

  @Test
  void test_runAction_successfullyEnqueuesDnsRefreshes() throws Exception {
    persistActiveDomain("foo.bar");
    persistActiveDomain("low.bar");
    action.tlds = ImmutableSet.of("bar");
    action.run();
    dnsUtilsHelper.assertDomainDnsRequestWithDelay("foo.bar", Duration.ZERO);
    dnsUtilsHelper.assertDomainDnsRequestWithDelay("low.bar", Duration.ZERO);
  }

  @Test
  void test_runAction_smearsOutDnsRefreshes() throws Exception {
    persistActiveDomain("foo.bar");
    persistActiveDomain("low.bar");
    action.tlds = ImmutableSet.of("bar");
    action.smearMinutes = 1000;
    action.run();
    dnsUtilsHelper.assertDomainDnsRequestWithDelay("foo.bar", Duration.standardMinutes(450));
    dnsUtilsHelper.assertDomainDnsRequestWithDelay("low.bar", Duration.standardMinutes(782));
  }

  @Test
  void test_runAction_doesntRefreshDeletedDomain() throws Exception {
    persistActiveDomain("foo.bar");
    persistDeletedDomain("deleted.bar", clock.nowUtc().minusYears(1));
    action.tlds = ImmutableSet.of("bar");
    action.run();
    dnsUtilsHelper.assertDomainDnsRequestWithDelay("foo.bar", Duration.ZERO);
    dnsUtilsHelper.assertNoDomainDnsRequestWithDelay("deleted.bar", Duration.ZERO);
  }

  @Test
  void test_runAction_ignoresDomainsOnOtherTlds() throws Exception {
    createTld("baz");
    persistActiveDomain("foo.bar");
    persistActiveDomain("low.bar");
    persistActiveDomain("ignore.baz");
    action.tlds = ImmutableSet.of("bar");
    action.run();
    dnsUtilsHelper.assertDomainDnsRequestWithDelay("foo.bar", Duration.ZERO);
    dnsUtilsHelper.assertDomainDnsRequestWithDelay("low.bar", Duration.ZERO);
    dnsUtilsHelper.assertNoDomainDnsRequestWithDelay("ignore.baz", Duration.ZERO);
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
}
