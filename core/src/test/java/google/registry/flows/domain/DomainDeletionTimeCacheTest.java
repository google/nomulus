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

package google.registry.flows.domain;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDomainAsDeleted;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import google.registry.model.domain.Domain;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link DomainDeletionTimeCache}. */
public class DomainDeletionTimeCacheTest {

  private final FakeClock clock = new FakeClock(DateTime.parse("2025-10-01T00:00:00.000Z"));
  private final DomainDeletionTimeCache cache = DomainDeletionTimeCache.create();

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    DatabaseHelper.createTld("tld");
  }

  @Test
  void testDomainAvailable_null() {
    assertThat(getDeletionTimeFromCache("nonexistent.tld")).isNull();
  }

  @Test
  void testDomainNotAvailable_notDeleted() {
    persistActiveDomain("active.tld");
    assertThat(getDeletionTimeFromCache("active.tld")).isEqualTo(END_OF_TIME);
  }

  @Test
  void testDomainAvailable_deletedInFuture() {
    persistDomainAsDeleted(persistActiveDomain("domain.tld"), clock.nowUtc().plusDays(1));
    assertThat(getDeletionTimeFromCache("domain.tld")).isEqualTo(clock.nowUtc().plusDays(1));
  }

  @Test
  void testCache_returnsOldData() {
    Domain domain = persistActiveDomain("domain.tld");
    assertThat(getDeletionTimeFromCache("domain.tld")).isEqualTo(END_OF_TIME);
    persistDomainAsDeleted(domain, clock.nowUtc().plusDays(1));
    // Without intervention, the cache should have the old data
    assertThat(getDeletionTimeFromCache("domain.tld")).isEqualTo(END_OF_TIME);
  }

  @Test
  void testCache_returnsNewDataAfterDomainCreate() {
    // Null deletion dates (meaning an avilable domain) shouldn't be cached
    assertThat(getDeletionTimeFromCache("domain.tld")).isNull();
    persistDomainAsDeleted(persistActiveDomain("domain.tld"), clock.nowUtc().plusDays(1));
    assertThat(getDeletionTimeFromCache("domain.tld")).isEqualTo(clock.nowUtc().plusDays(1));
  }

  private DateTime getDeletionTimeFromCache(String domainName) {
    return tm().transact(() -> cache.getDeletionTimeForDomain(domainName));
  }
}
