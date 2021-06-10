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
import static google.registry.testing.TaskQueueHelper.assertDnsTasksEnqueued;
import static org.joda.time.Duration.standardMinutes;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableSet;
import google.registry.dns.DnsQueue;
import google.registry.model.ofy.Ofy;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.InjectExtension;
import google.registry.testing.TestOfyAndSql;
import google.registry.testing.TestSqlOnly;
import google.registry.testing.mapreduce.MapreduceTestCase;
import google.registry.tools.server.RefreshDnsForAllDomainsAction.RefreshDnsForAllDomainsActionMapper;
import java.util.Random;
import org.apache.http.HttpStatus;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link RefreshDnsForAllDomainsAction}. */
@DualDatabaseTest
public class RefreshDnsForAllDomainsActionTest
    extends MapreduceTestCase<RefreshDnsForAllDomainsAction> {

  private final FakeClock clock = new FakeClock(DateTime.parse("2020-02-02T02:02:02Z"));
  private final DnsQueue dnsQueue = mock(DnsQueue.class);
  private DnsQueue origDnsQueue;
  private FakeResponse response = new FakeResponse();

  @Order(Order.DEFAULT - 1)
  @RegisterExtension
  public final InjectExtension inject =
      new InjectExtension().withStaticFieldOverride(Ofy.class, "clock", clock);

  @BeforeEach
  void beforeEach() {
    origDnsQueue = RefreshDnsForAllDomainsActionMapper.setDnsQueueForTest(dnsQueue);

    action = new RefreshDnsForAllDomainsAction();
    action.smearMinutes = 1;
    action.random = new Random();
    action.random.setSeed(123L);
    action.mrRunner = makeDefaultRunner();
    action.response = new FakeResponse();
    action.clock = clock;
    action.dnsQueue = dnsQueue;
    action.response = response;

    createTld("bar");
  }

  @AfterEach
  void afterEach() {
    assertThat(RefreshDnsForAllDomainsActionMapper.setDnsQueueForTest(origDnsQueue))
        .isEqualTo(dnsQueue);
  }

  private void runAction() throws Exception {
    action.run();
    executeTasksUntilEmpty("mapreduce");
  }

  @TestSqlOnly
  void test_runAction_errorEnqueuingToDnsQueue() throws Exception {
    persistActiveDomain("foo.bar");
    persistActiveDomain("baz.bar");
    persistActiveDomain("low.bar");
    action.tlds = ImmutableSet.of("bar");
    DnsQueue faultyQueue = spy(origDnsQueue);
    doThrow(new RuntimeException("Error enqueuing task."))
        .when(faultyQueue)
        .addDomainRefreshTask(eq("baz.bar"), any(Duration.class));
    action.dnsQueue = faultyQueue;
    runAction();
    assertDnsTasksEnqueued("foo.bar", "low.bar");
    assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_INTERNAL_SERVER_ERROR);
  }

  @TestOfyAndSql
  void test_runAction_successfullyEnqueuesDnsRefreshes() throws Exception {
    persistActiveDomain("foo.bar");
    persistActiveDomain("low.bar");
    action.tlds = ImmutableSet.of("bar");
    runAction();
    verify(dnsQueue).addDomainRefreshTask("foo.bar", Duration.ZERO);
    verify(dnsQueue).addDomainRefreshTask("low.bar", Duration.ZERO);
  }

  @TestOfyAndSql
  void test_runAction_smearsOutDnsRefreshes() throws Exception {
    persistActiveDomain("foo.bar");
    persistActiveDomain("low.bar");
    action.tlds = ImmutableSet.of("bar");
    action.smearMinutes = 1000;
    runAction();
    ArgumentCaptor<Duration> captor = ArgumentCaptor.forClass(Duration.class);
    verify(dnsQueue).addDomainRefreshTask(eq("foo.bar"), captor.capture());
    verify(dnsQueue).addDomainRefreshTask(eq("low.bar"), captor.capture());
    assertThat(captor.getAllValues()).containsExactly(standardMinutes(450), standardMinutes(782));
  }

  @TestOfyAndSql
  void test_runAction_doesntRefreshDeletedDomain() throws Exception {
    persistActiveDomain("foo.bar");
    persistDeletedDomain("deleted.bar", clock.nowUtc().minusYears(1));
    action.tlds = ImmutableSet.of("bar");
    runAction();
    verify(dnsQueue).addDomainRefreshTask("foo.bar", Duration.ZERO);
    verify(dnsQueue, never()).addDomainRefreshTask("deleted.bar", Duration.ZERO);
  }

  @TestOfyAndSql
  void test_runAction_ignoresDomainsOnOtherTlds() throws Exception {
    createTld("baz");
    persistActiveDomain("foo.bar");
    persistActiveDomain("low.bar");
    persistActiveDomain("ignore.baz");
    action.tlds = ImmutableSet.of("bar");
    runAction();
    verify(dnsQueue).addDomainRefreshTask("foo.bar", Duration.ZERO);
    verify(dnsQueue).addDomainRefreshTask("low.bar", Duration.ZERO);
    verify(dnsQueue, never()).addDomainRefreshTask("ignore.baz", Duration.ZERO);
  }

  @TestOfyAndSql
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
