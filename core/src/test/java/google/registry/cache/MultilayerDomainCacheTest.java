// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.cache;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_INSTANT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import google.registry.model.domain.Domain;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.tld.Tld;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link MultilayerDomainCache}. */
public class MultilayerDomainCacheTest {

  private static final Instant START_TIME = Instant.parse("2025-01-01T00:00:00Z");
  private static final Duration XAP_LENGTH = Duration.ofDays(10);
  private final FakeClock clock = new FakeClock(START_TIME);

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  private final SimplifiedJedisClient jedisClient = mock(SimplifiedJedisClient.class);
  private final CacheMetrics cacheMetrics = mock(CacheMetrics.class);
  private MultilayerDomainCache cache;

  @BeforeEach
  void beforeEach() {
    cache = new MultilayerDomainCache(jedisClient, clock, cacheMetrics, XAP_LENGTH);
    createTld("tld");
  }

  private static SimplifiedJedisClient.JedisResource<Domain> expectedDomainResource(
      String domainName, Domain domain) {
    return new SimplifiedJedisClient.JedisResource<>(
        domainName, domain, domain.getDeletionTime().plus(XAP_LENGTH));
  }

  @Test
  void testLoad_fromDatabase_populatesCaches() {
    Domain domain = persistActiveDomain("example.tld");
    assertThat(cache.loadByDomainName("example.tld")).hasValue(domain);

    // We should have filled the caches after one attempt to load from Valkey
    verify(jedisClient).get(Domain.class, "example.tld");
    verify(jedisClient).set(expectedDomainResource("example.tld", domain));
    verify(cacheMetrics).recordLookup("Domain", CacheMetrics.CacheHitType.MISS);

    // Further loads hit the local cache
    assertThat(cache.loadByDomainName("example.tld")).hasValue(domain);
    verify(cacheMetrics).recordLookup("Domain", CacheMetrics.CacheHitType.LOCAL);
    verifyNoMoreInteractions(jedisClient);
    verifyNoMoreInteractions(cacheMetrics);
  }

  @Test
  void testLoad_fromValkey() {
    // Note: we don't save the domain to SQL
    Domain domain = DatabaseHelper.newDomain("example.tld");
    // We hit the Valkey cache first
    when(jedisClient.get(Domain.class, "example.tld")).thenReturn(Optional.of(domain));
    assertThat(cache.loadByDomainName("example.tld")).hasValue(domain);
    verify(cacheMetrics).recordLookup("Domain", CacheMetrics.CacheHitType.REMOTE);
    verifyNoMoreInteractions(cacheMetrics);
  }

  @Test
  void testSkipsTestTld() {
    persistResource(Tld.get("tld").asBuilder().setTldType(Tld.TldType.TEST).build());

    Domain domain = persistActiveDomain("example.tld");
    assertThat(cache.loadByDomainName("example.tld")).hasValue(domain);

    // This time, we don't populate the remote cache because it's prober data
    verify(jedisClient).get(Domain.class, "example.tld");
    verify(cacheMetrics).recordLookup("Domain", CacheMetrics.CacheHitType.MISS);
    verifyNoMoreInteractions(jedisClient);
    verifyNoMoreInteractions(cacheMetrics);
  }

  @Test
  void testLoad_missing() {
    assertThat(cache.loadByDomainName("nonexistent.tld")).isEmpty();
    verify(cacheMetrics).recordLookup("Domain", CacheMetrics.CacheHitType.MISS_NONEXISTENT);
    verifyNoMoreInteractions(cacheMetrics);
  }

  @Test
  void testLoad_filtersOutDeletedDomain() {
    Domain domain =
        persistActiveDomain("example.tld")
            .asBuilder()
            .setDeletionTime(clock.now().plus(Duration.ofDays(1)))
            .build();
    when(jedisClient.get(Domain.class, "example.tld")).thenReturn(Optional.of(domain));
    assertThat(cache.loadByDomainName("example.tld")).hasValue(domain);

    clock.advanceBy(Duration.ofDays(2));
    assertThat(cache.loadByDomainName("example.tld")).isEmpty();
  }

  @Test
  void testLoad_projectsToCurrentTime() {
    Domain domain =
        persistActiveDomain("example.tld")
            .asBuilder()
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.ADD,
                    "example.tld",
                    clock.now().plus(Duration.ofDays(5)),
                    "TheRegistrar",
                    null))
            .build();
    when(jedisClient.get(Domain.class, "example.tld")).thenReturn(Optional.of(domain));
    assertThat(cache.loadByDomainName("example.tld").get().getGracePeriods())
        .containsExactlyElementsIn(domain.getGracePeriods());

    clock.advanceBy(Duration.ofDays(10));
    assertThat(cache.loadByDomainName("example.tld").get().getGracePeriods()).isEmpty();
  }

  @Test
  void testLoadIncludingDeleted_includesDeletedDomain() {
    Domain domain =
        persistActiveDomain("example.tld")
            .asBuilder()
            .setDeletionTime(START_TIME.minus(Duration.ofDays(1)))
            .build();
    when(jedisClient.get(Domain.class, "example.tld")).thenReturn(Optional.of(domain));
    assertThat(cache.loadByDomainName("example.tld")).isEmpty();
    assertThat(cache.loadByDomainNameIncludingDeleted("example.tld")).hasValue(domain);
  }

  @Test
  void testProvideDomainCache_noJedisClient_loadsActiveAndDeletedDomains() {
    DomainCache dbOnlyCache =
        CacheModule.provideDomainCache(Optional.empty(), clock, cacheMetrics, XAP_LENGTH);
    Domain activeDomain = persistActiveDomain("active-db.tld");
    Domain deletedDomain =
        persistDeletedDomain("deleted-db.tld", START_TIME.minus(Duration.ofDays(1)));

    assertThat(dbOnlyCache.loadByDomainName("active-db.tld")).hasValue(activeDomain);
    assertThat(dbOnlyCache.loadByDomainName("deleted-db.tld")).isEmpty();
    assertThat(dbOnlyCache.loadByDomainName("nonexistent-db.tld")).isEmpty();

    assertThat(dbOnlyCache.loadByDomainNameIncludingDeleted("active-db.tld"))
        .hasValue(activeDomain);
    assertThat(dbOnlyCache.loadByDomainNameIncludingDeleted("deleted-db.tld"))
        .hasValue(deletedDomain);
    assertThat(dbOnlyCache.loadByDomainNameIncludingDeleted("nonexistent-db.tld")).isEmpty();
  }

  @Test
  void testLoadIncludingDeleted_recentlyDeletedDomain_populatesValkeyWithCalculatedTtl() {
    Instant deletionTime = START_TIME.minus(Duration.ofDays(2));
    Domain domain = persistDeletedDomain("xap.tld", deletionTime);

    assertThat(cache.loadByDomainNameIncludingDeleted("xap.tld")).hasValue(domain);

    verify(jedisClient).get(Domain.class, "xap.tld");
    verify(jedisClient).set(expectedDomainResource("xap.tld", domain));
    verify(cacheMetrics).recordLookup("Domain", CacheMetrics.CacheHitType.MISS);
  }

  @Test
  void testLoad_pendingDeleteDomain_populatesValkeyThroughEndOfXap() {
    Instant futureDeletionTime = START_TIME.plus(Duration.ofDays(5));
    Domain pendingDeleteDomain = persistDeletedDomain("pending-del.tld", futureDeletionTime);

    assertThat(cache.loadByDomainName("pending-del.tld")).hasValue(pendingDeleteDomain);

    verify(jedisClient).get(Domain.class, "pending-del.tld");
    verify(jedisClient)
        .set(
            new SimplifiedJedisClient.JedisResource<>(
                "pending-del.tld", pendingDeleteDomain, futureDeletionTime.plus(XAP_LENGTH)));
    verify(cacheMetrics).recordLookup("Domain", CacheMetrics.CacheHitType.MISS);
  }

  @Test
  void testLoadIncludingDeleted_softDeleted_pastXapWindow_doesNotPersistToValkey() {
    Domain domain = persistDeletedDomain("past-xap.tld", START_TIME.minus(Duration.ofDays(11)));

    assertThat(cache.loadByDomainNameIncludingDeleted("past-xap.tld")).hasValue(domain);

    verify(jedisClient).get(Domain.class, "past-xap.tld");
    verify(jedisClient, never()).set(any());
    verify(cacheMetrics).recordLookup("Domain", CacheMetrics.CacheHitType.MISS);
  }

  @Test
  void testLoadIncludingDeleted_softDeleted_testTld_doesNotPersistToValkey() {
    persistResource(Tld.get("tld").asBuilder().setTldType(Tld.TldType.TEST).build());

    Domain domain = persistDeletedDomain("test-tld.tld", START_TIME.minus(Duration.ofDays(2)));

    assertThat(cache.loadByDomainNameIncludingDeleted("test-tld.tld")).hasValue(domain);

    verify(jedisClient).get(Domain.class, "test-tld.tld");
    verify(jedisClient, never()).set(any());
    verify(cacheMetrics).recordLookup("Domain", CacheMetrics.CacheHitType.MISS);
  }

  @Test
  void testShouldPersistToRemoteCache_and_getExpirationTime_boundaries() {
    // 1. Active domain (END_INSTANT and future pending-delete)
    Domain activeDomain = persistActiveDomain("active.tld");
    assertThat(cache.shouldPersistToRemoteCache(activeDomain)).isTrue();
    assertThat(cache.getExpirationTime(activeDomain)).hasValue(END_INSTANT.plus(XAP_LENGTH));
    Instant futureDeletion = START_TIME.plus(Duration.ofDays(3));
    Domain pendingDeleteDomain = persistDeletedDomain("pending-del.tld", futureDeletion);
    assertThat(cache.shouldPersistToRemoteCache(pendingDeleteDomain)).isTrue();
    assertThat(cache.getExpirationTime(pendingDeleteDomain))
        .hasValue(futureDeletion.plus(XAP_LENGTH));
    assertThat(
            new SimplifiedJedisClient.JedisResource<>(
                    "pending-del.tld",
                    pendingDeleteDomain,
                    cache.getExpirationTime(pendingDeleteDomain))
                .getExpirationTime())
        .isEqualTo(futureDeletion.plus(XAP_LENGTH));

    // 2. Boundary: deletionTime == START_TIME
    Domain deletedAtNow = persistDeletedDomain("del-now.tld", START_TIME);
    assertThat(cache.shouldPersistToRemoteCache(deletedAtNow)).isTrue();
    assertThat(cache.getExpirationTime(deletedAtNow)).hasValue(START_TIME.plus(XAP_LENGTH));

    // 3. Boundary: deletionTime == START_TIME - 10d + 1ms (strictly inside 10d window)
    Instant insideWindow = START_TIME.minus(XAP_LENGTH).plusMillis(1);
    Domain deletedInsideWindow = persistDeletedDomain("inside.tld", insideWindow);
    assertThat(cache.shouldPersistToRemoteCache(deletedInsideWindow)).isTrue();
    assertThat(cache.getExpirationTime(deletedInsideWindow))
        .hasValue(insideWindow.plus(XAP_LENGTH));

    // 4. Boundary: deletionTime == START_TIME - 10d (exact edge of 10d window)
    Instant exactEdge = START_TIME.minus(XAP_LENGTH);
    Domain deletedExactEdge = persistDeletedDomain("exact-edge.tld", exactEdge);
    assertThat(cache.shouldPersistToRemoteCache(deletedExactEdge)).isFalse();
    assertThat(cache.getExpirationTime(deletedExactEdge)).hasValue(exactEdge.plus(XAP_LENGTH));

    // 5. Boundary: deletionTime == START_TIME - 10d - 1ms (strictly outside window)
    Instant outsideWindow = START_TIME.minus(XAP_LENGTH).minusMillis(1);
    Domain deletedOutsideWindow = persistDeletedDomain("outside.tld", outsideWindow);
    assertThat(cache.shouldPersistToRemoteCache(deletedOutsideWindow)).isFalse();
    assertThat(cache.getExpirationTime(deletedOutsideWindow))
        .hasValue(outsideWindow.plus(XAP_LENGTH));

    // 6. Custom length constructor (15 days)
    MultilayerDomainCache customCache =
        new MultilayerDomainCache(jedisClient, clock, cacheMetrics, Duration.ofDays(15));
    Instant twelveDaysAgo = START_TIME.minus(Duration.ofDays(12));
    Domain deletedTwelveDaysAgo = persistDeletedDomain("twelve-days.tld", twelveDaysAgo);
    // In default 10-day cache: expired
    assertThat(cache.shouldPersistToRemoteCache(deletedTwelveDaysAgo)).isFalse();
    assertThat(cache.getExpirationTime(deletedTwelveDaysAgo))
        .hasValue(twelveDaysAgo.plus(XAP_LENGTH));
    // In custom 15-day cache: still within window
    assertThat(customCache.shouldPersistToRemoteCache(deletedTwelveDaysAgo)).isTrue();
    assertThat(customCache.getExpirationTime(deletedTwelveDaysAgo))
        .hasValue(twelveDaysAgo.plus(Duration.ofDays(15)));
  }

  @Test
  void testRapidRecreationAndDeletionCycle_transitionsCacheAndTtlCorrectly() {
    // 1. Initial soft deletion within retention window
    Instant t1Del = START_TIME.minus(Duration.ofDays(2));
    Domain domainV1 =
        persistResource(
            persistDeletedDomain("cycle.tld", t1Del)
                .asBuilder()
                .setCreationTimeForTest(START_TIME.minus(Duration.ofDays(8)))
                .build());

    MultilayerDomainCache cache1 =
        new MultilayerDomainCache(jedisClient, clock, cacheMetrics, XAP_LENGTH);
    assertThat(cache1.loadByDomainNameIncludingDeleted("cycle.tld")).hasValue(domainV1);
    verify(jedisClient).set(expectedDomainResource("cycle.tld", domainV1));

    // 2. Domain re-registered (active)
    clearInvocations(jedisClient, cacheMetrics);
    Instant t2Create = START_TIME.minus(Duration.ofDays(1));
    Domain domainV2 =
        persistResource(
            persistActiveDomain("cycle.tld")
                .asBuilder()
                .setCreationTimeForTest(t2Create)
                .setDeletionTime(END_INSTANT)
                .build());

    MultilayerDomainCache cache2 =
        new MultilayerDomainCache(jedisClient, clock, cacheMetrics, XAP_LENGTH);
    assertThat(cache2.loadByDomainNameIncludingDeleted("cycle.tld")).hasValue(domainV2);
    verify(jedisClient).set(expectedDomainResource("cycle.tld", domainV2));

    // 3. Domain deleted again -> new deletionTime & new TTL
    clearInvocations(jedisClient, cacheMetrics);
    Instant t3Del = START_TIME.plus(Duration.ofDays(6));
    clock.setTo(t3Del.plus(Duration.ofDays(1)));
    Domain domainV3 =
        persistResource(
            domainV2.asBuilder().setCreationTimeForTest(t2Create).setDeletionTime(t3Del).build());

    MultilayerDomainCache cache3 =
        new MultilayerDomainCache(jedisClient, clock, cacheMetrics, XAP_LENGTH);
    assertThat(cache3.loadByDomainNameIncludingDeleted("cycle.tld")).hasValue(domainV3);
    verify(jedisClient).set(expectedDomainResource("cycle.tld", domainV3));
  }
}
