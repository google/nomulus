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
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistDeletedDomain;
import static org.joda.time.DateTimeZone.UTC;
import static org.joda.time.Duration.standardMinutes;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableSet;
import google.registry.dns.DnsQueue;
import google.registry.testing.FakeResponse;
import google.registry.testing.InjectExtension;
import google.registry.testing.mapreduce.MapreduceTestCase;
import google.registry.tools.server.RefreshDnsForAllDomainsAction.RefreshDnsForAllDomainsActionMapper;
import java.util.Random;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link RefreshDnsForAllDomainsAction}. */
public class RefreshDnsForAllDomainsActionTest
    extends MapreduceTestCase<RefreshDnsForAllDomainsAction> {

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  private final DnsQueue dnsQueue = mock(DnsQueue.class);
  private DnsQueue origDnsQueue;

  @BeforeEach
  void beforeEach() {
    origDnsQueue = RefreshDnsForAllDomainsActionMapper.setDnsQueueForTest(dnsQueue);

    action = new RefreshDnsForAllDomainsAction();
    action.smearMinutes = 1;
    action.random = new Random();
    action.random.setSeed(123L);
    action.mrRunner = makeDefaultRunner();
    action.response = new FakeResponse();

    createTld("bar");
  }

  @AfterEach
  void afterEach() {
    assertThat(RefreshDnsForAllDomainsActionMapper.setDnsQueueForTest(origDnsQueue))
        .isEqualTo(dnsQueue);
  }

  private void runMapreduce() throws Exception {
    action.run();
    executeTasksUntilEmpty("mapreduce");
  }

  @Test
  void test_runAction_successfullyEnqueuesDnsRefreshes() throws Exception {
    persistActiveDomain("foo.bar");
    persistActiveDomain("low.bar");
    action.tlds = ImmutableSet.of("bar");
    runMapreduce();
    verify(dnsQueue).addDomainRefreshTask("foo.bar", Duration.ZERO);
    verify(dnsQueue).addDomainRefreshTask("low.bar", Duration.ZERO);
  }

  @Test
  void test_runAction_smearsOutDnsRefreshes() throws Exception {
    persistActiveDomain("foo.bar");
    persistActiveDomain("low.bar");
    action.tlds = ImmutableSet.of("bar");
    action.smearMinutes = 1000;
    runMapreduce();
    ArgumentCaptor<Duration> captor = ArgumentCaptor.forClass(Duration.class);
    verify(dnsQueue).addDomainRefreshTask(eq("foo.bar"), captor.capture());
    verify(dnsQueue).addDomainRefreshTask(eq("low.bar"), captor.capture());
    assertThat(captor.getAllValues()).containsExactly(standardMinutes(450), standardMinutes(782));
  }

  @Test
  void test_runAction_doesntRefreshDeletedDomain() throws Exception {
    persistActiveDomain("foo.bar");
    persistDeletedDomain("deleted.bar", DateTime.now(UTC).minusYears(1));
    action.tlds = ImmutableSet.of("bar");
    runMapreduce();
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
    runMapreduce();
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
