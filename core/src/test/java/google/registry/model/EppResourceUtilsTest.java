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

package google.registry.model;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.EppResourceUtils.loadAtPointInTime;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newHost;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;

import google.registry.model.host.Host;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link EppResourceUtils}. */
class EppResourceUtilsTest {

  private final FakeClock clock = new FakeClock(DateTime.now(UTC));

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    createTld("tld");
  }

  @Test
  void testLoadAtPointInTime_beforeCreated_returnsNull() {
    clock.advanceOneMilli();
    // Don't save a commit log, we shouldn't need one.
    Host host =
        persistResource(
            newHost("ns1.cat.tld").asBuilder().setCreationTimeForTest(clock.nowUtc()).build());
    assertThat(loadAtPointInTime(host, clock.nowUtc().minus(Duration.millis(1)))).isNull();
  }

  @Test
  void testLoadAtPointInTime_atOrAfterLastAutoUpdateTime_returnsResource() {
    clock.advanceOneMilli();
    // Don't save a commit log, we shouldn't need one.
    Host host =
        persistResource(
            newHost("ns1.cat.tld").asBuilder().setCreationTimeForTest(START_OF_TIME).build());
    assertThat(loadAtPointInTime(host, clock.nowUtc())).isEqualTo(host);
  }
}
