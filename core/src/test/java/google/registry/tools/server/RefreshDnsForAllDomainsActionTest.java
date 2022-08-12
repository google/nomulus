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
import static org.joda.time.Duration.standardMinutes;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.collect.ImmutableSet;
import google.registry.dns.DnsQueue;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import java.util.Random;
import org.apache.http.HttpStatus;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** Unit tests for {@link RefreshDnsForAllDomainsAction}. */
public class RefreshDnsForAllDomainsActionTest {

  private final FakeClock clock = new FakeClock(DateTime.parse("2020-02-02T02:02:02Z"));
  private final DnsQueue dnsQueue = mock(DnsQueue.class);
  private RefreshDnsForAllDomainsAction action;
  private final FakeResponse response = new FakeResponse();

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withCloudSql().withLocalModules().withTaskQueue().build();

  @BeforeEach
  void beforeEach() {
    action = new RefreshDnsForAllDomainsAction();
    action.smearMinutes = 1;
    action.random = new Random();
    action.random.setSeed(123L);
    action.clock = clock;
    action.dnsQueue = dnsQueue;
    action.response = response;

    createTld("bar");
  }

  @Test
  void test_runAction_errorEnqueuingToDnsQueue() throws Exception {
    persistActiveDomain("foo.bar");
    persistActiveDomain("baz.bar");
    persistActiveDomain("low.bar");
    action.tlds = ImmutableSet.of("bar");
    doThrow(new RuntimeException("Error enqueuing task."))
        .when(dnsQueue)
        .addDomainRefreshTask(eq("baz.bar"), any(Duration.class));
    action.run();
    InOrder inOrder = inOrder(dnsQueue);
    inOrder.verify(dnsQueue).addDomainRefreshTask("low.bar", Duration.ZERO);
    inOrder.verify(dnsQueue).addDomainRefreshTask("baz.bar", Duration.ZERO);
    inOrder.verify(dnsQueue).addDomainRefreshTask("foo.bar", Duration.ZERO);
    verifyNoMoreInteractions(dnsQueue);
    assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_INTERNAL_SERVER_ERROR);
  }

  @Test
  void test_runAction_successfullyEnqueuesDnsRefreshes() throws Exception {
    persistActiveDomain("foo.bar");
    persistActiveDomain("low.bar");
    action.tlds = ImmutableSet.of("bar");
    action.run();
    verify(dnsQueue).addDomainRefreshTask("foo.bar", Duration.ZERO);
    verify(dnsQueue).addDomainRefreshTask("low.bar", Duration.ZERO);
  }

  @Test
  void test_runAction_smearsOutDnsRefreshes() throws Exception {
    persistActiveDomain("foo.bar");
    persistActiveDomain("low.bar");
    action.tlds = ImmutableSet.of("bar");
    action.smearMinutes = 1000;
    action.run();
    ArgumentCaptor<Duration> captor = ArgumentCaptor.forClass(Duration.class);
    verify(dnsQueue).addDomainRefreshTask(eq("foo.bar"), captor.capture());
    verify(dnsQueue).addDomainRefreshTask(eq("low.bar"), captor.capture());
    assertThat(captor.getAllValues()).containsExactly(standardMinutes(450), standardMinutes(782));
  }

  @Test
  void test_runAction_doesntRefreshDeletedDomain() throws Exception {
    persistActiveDomain("foo.bar");
    persistDeletedDomain("deleted.bar", clock.nowUtc().minusYears(1));
    action.tlds = ImmutableSet.of("bar");
    action.run();
    verify(dnsQueue).addDomainRefreshTask("foo.bar", Duration.ZERO);
    verify(dnsQueue, never()).addDomainRefreshTask("deleted.bar", Duration.ZERO);
  }

  @Test
  void test_runAction_ignoresDomainsOnOtherTlds() throws Exception {
    createTld("baz");
    persistActiveDomain("foo.bar");
    persistActiveDomain("low.bar");
    persistActiveDomain("ignore.baz");
    action.tlds = ImmutableSet.of("bar");
    action.run();
    verify(dnsQueue).addDomainRefreshTask("foo.bar", Duration.ZERO);
    verify(dnsQueue).addDomainRefreshTask("low.bar", Duration.ZERO);
    verify(dnsQueue, never()).addDomainRefreshTask("ignore.baz", Duration.ZERO);
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
