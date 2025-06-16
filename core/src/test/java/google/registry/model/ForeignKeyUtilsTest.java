// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.TestCacheExtension;
import java.time.Duration;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ForeignKeyUtils}. */
class ForeignKeyUtilsTest {

  private final FakeClock fakeClock = new FakeClock(DateTime.now(UTC));

  @RegisterExtension
  public final JpaIntegrationTestExtension jpaIntegrationTestExtension =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationTestExtension();

  @RegisterExtension
  public final TestCacheExtension testCacheExtension =
      new TestCacheExtension.Builder().withForeignKeyCache(Duration.ofDays(1)).build();

  @BeforeEach
  void setUp() {
    createTld("com");
  }

  @Test
  void testSuccess_loadHost() {
    Host host = persistActiveHost("ns1.example.com");
    assertThat(ForeignKeyUtils.load(Host.class, "ns1.example.com", fakeClock.nowUtc()))
        .isEqualTo(host.createVKey());
  }

  @Test
  void testSuccess_loadDomain() {
    Domain domain = persistActiveDomain("example.com");
    assertThat(ForeignKeyUtils.load(Domain.class, "example.com", fakeClock.nowUtc()))
        .isEqualTo(domain.createVKey());
  }

  @Test
  void testSuccess_loadContact() {
    Contact contact = persistActiveContact("john-doe");
    assertThat(ForeignKeyUtils.load(Contact.class, "john-doe", fakeClock.nowUtc()))
        .isEqualTo(contact.createVKey());
  }

  @Test
  void testSuccess_loadMostRecentResource() {
    Host host = persistActiveHost("ns1.example.com");
    persistResource(host.asBuilder().setDeletionTime(fakeClock.nowUtc().minusDays(1)).build());
    fakeClock.advanceOneMilli();
    Host newHost = persistActiveHost("ns1.example.com");
    assertThat(ForeignKeyUtils.load(Host.class, "ns1.example.com", fakeClock.nowUtc()))
        .isEqualTo(newHost.createVKey());
  }

  @Test
  void testSuccess_loadNonexistentForeignKey_returnsNull() {
    assertThat(ForeignKeyUtils.load(Host.class, "ns1.example.com", fakeClock.nowUtc())).isNull();
  }

  @Test
  void testSuccess_loadDeletedForeignKey_returnsNull() {
    Host host = persistActiveHost("ns1.example.com");
    persistResource(host.asBuilder().setDeletionTime(fakeClock.nowUtc().minusDays(1)).build());
    assertThat(ForeignKeyUtils.load(Host.class, "ns1.example.com", fakeClock.nowUtc())).isNull();
  }

  @Test
  void testSuccess_mostRecentKeySoftDeleted_returnsNull() {
    Host host1 = persistActiveHost("ns1.example.com");
    fakeClock.advanceOneMilli();
    persistResource(host1.asBuilder().setDeletionTime(fakeClock.nowUtc()).build());
    assertThat(ForeignKeyUtils.load(Host.class, "ns1.example.com", fakeClock.nowUtc())).isNull();
  }

  @Test
  void testSuccess_batchLoad_skipsDeletedAndNonexistent() {
    Host host1 = persistActiveHost("ns1.example.com");
    Host host2 = persistActiveHost("ns2.example.com");
    persistResource(host2.asBuilder().setDeletionTime(fakeClock.nowUtc().minusDays(1)).build());
    assertThat(
            ForeignKeyUtils.load(
                Host.class,
                ImmutableList.of("ns1.example.com", "ns2.example.com", "ns3.example.com"),
                fakeClock.nowUtc()))
        .containsExactlyEntriesIn(ImmutableMap.of("ns1.example.com", host1.createVKey()));
    persistResource(host1.asBuilder().setDeletionTime(fakeClock.nowUtc()).build());
    fakeClock.advanceOneMilli();
    Host newHost1 = persistActiveHost("ns1.example.com");
    assertThat(
            ForeignKeyUtils.loadByCacheIfEnabled(
                Host.class,
                ImmutableList.of("ns1.example.com", "ns2.example.com", "ns3.example.com"),
                fakeClock.nowUtc()))
        .containsExactlyEntriesIn(ImmutableMap.of("ns1.example.com", newHost1.createVKey()));
  }

  @Test
  void testSuccess_loadHostsCached_cacheIsStale() {
    Host host1 = persistActiveHost("ns1.example.com");
    Host host2 = persistActiveHost("ns2.example.com");
    persistResource(host2.asBuilder().setDeletionTime(fakeClock.nowUtc().minusDays(1)).build());
    assertThat(
            ForeignKeyUtils.loadByCacheIfEnabled(
                Host.class,
                ImmutableList.of("ns1.example.com", "ns2.example.com", "ns3.example.com"),
                fakeClock.nowUtc()))
        .containsExactlyEntriesIn(ImmutableMap.of("ns1.example.com", host1.createVKey()));
    persistResource(host1.asBuilder().setDeletionTime(fakeClock.nowUtc()).build());
    fakeClock.advanceOneMilli();
    persistActiveHost("ns1.example.com");
    // Even though a new host1 is now live, the cache still returns the VKey to the old one.
    assertThat(
            ForeignKeyUtils.loadByCacheIfEnabled(
                Host.class,
                ImmutableList.of("ns1.example.com", "ns2.example.com", "ns3.example.com"),
                fakeClock.nowUtc()))
        .containsExactlyEntriesIn(ImmutableMap.of("ns1.example.com", host1.createVKey()));
  }
}
