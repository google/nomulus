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

package google.registry.dns;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link DnsQueue}. */
public class DnsQueueTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  private DnsQueue dnsQueue;
  private final FakeClock clock = new FakeClock(DateTime.parse("2010-01-01T10:00:00Z"));

  @BeforeEach
  void beforeEach() {
    dnsQueue = DnsQueue.createForTesting(clock);
    dnsQueue.leaseTasksBatchSize = 10;
  }

  @Test
  void test_addHostRefreshTask_success() {
    createTld("tld");
    dnsQueue.addHostRefreshTask("octopus.tld");
    assertTasksEnqueued(
        "dns-pull",
        new TaskMatcher()
            .param("Target-Type", "HOST")
            .param("Target-Name", "octopus.tld")
            .param("Create-Time", "2010-01-01T10:00:00.000Z")
            .param("tld", "tld"));
  }

  @Test
  void test_addHostRefreshTask_failsOnUnknownTld() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> {
              try {
                dnsQueue.addHostRefreshTask("octopus.notatld");
              } finally {
                assertNoTasksEnqueued("dns-pull");
              }
            });
    assertThat(thrown)
        .hasMessageThat()
        .contains("octopus.notatld is not a subordinate host to a known tld");
  }

  @Test
  void test_addDomainRefreshTask_success() {
    createTld("tld");
    dnsQueue.addDomainRefreshTask("octopus.tld");
    assertTasksEnqueued(
        "dns-pull",
        new TaskMatcher()
            .param("Target-Type", "DOMAIN")
            .param("Target-Name", "octopus.tld")
            .param("Create-Time", "2010-01-01T10:00:00.000Z")
            .param("tld", "tld"));
  }

  @Test
  void test_addDomainRefreshTask_failsOnUnknownTld() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> {
              try {
                dnsQueue.addDomainRefreshTask("fake.notatld");
              } finally {
                assertNoTasksEnqueued("dns-pull");
              }
            });
    assertThat(thrown).hasMessageThat().contains("TLD notatld does not exist");
  }
}
