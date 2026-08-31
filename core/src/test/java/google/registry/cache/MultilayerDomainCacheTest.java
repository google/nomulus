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
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSortedMap;
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

  private final FakeClock clock = new FakeClock(Instant.parse("2025-01-01T00:00:00Z"));

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  private final SimplifiedJedisClient jedisClient = mock(SimplifiedJedisClient.class);
  private final CacheMetrics cacheMetrics = mock(CacheMetrics.class);
  private MultilayerDomainCache cache;

  @BeforeEach
  void beforeEach() {
    cache = new MultilayerDomainCache(jedisClient, clock, cacheMetrics);
    createTld("tld");
  }

  @Test
  void testLoad_fromDatabase_populatesCaches() {
    Domain domain = persistActiveDomain("example.tld");
    assertThat(cache.loadByDomainName("example.tld")).hasValue(domain);

    // We should have filled the caches after one attempt to load from Valkey
    verify(jedisClient).get(Domain.class, "example.tld");
    verify(jedisClient).set(new SimplifiedJedisClient.JedisResource<>("example.tld", domain));
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
  void testLoadMostRecent_includesDeletedDomain() {
    Domain domain =
        persistActiveDomain("example.tld")
            .asBuilder()
            .setDeletionTime(clock.now().minus(Duration.ofDays(1)))
            .build();
    when(jedisClient.get(Domain.class, "example.tld")).thenReturn(Optional.of(domain));
    assertThat(cache.loadByDomainName("example.tld")).isEmpty();
    assertThat(cache.loadMostRecentByDomainName("example.tld")).hasValue(domain);
  }

  @Test
  void testLoadMostRecent_xapDomain_populatesValkeyWithCalculatedTtl() {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, Tld.ExpiryAccessPeriodMode.ENABLED))
            .build());

    Instant deletionTime = clock.now().minus(Duration.ofDays(2));
    Domain domain = persistDeletedDomain("xap.tld", deletionTime);

    assertThat(cache.loadMostRecentByDomainName("xap.tld")).hasValue(domain);

    Instant expectedExpiration = deletionTime.plus(Duration.ofDays(10));
    verify(jedisClient).get(Domain.class, "xap.tld");
    verify(jedisClient)
        .set(new SimplifiedJedisClient.JedisResource<>("xap.tld", domain, expectedExpiration));
    verify(cacheMetrics).recordLookup("Domain", CacheMetrics.CacheHitType.MISS);
  }

  @Test
  void testLoadMostRecent_softDeleted_agpDelete_doesNotPersistToValkey() {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, Tld.ExpiryAccessPeriodMode.ENABLED))
            .build());

    Domain domain =
        persistResource(
            persistDeletedDomain("agp.tld", clock.now().minus(Duration.ofDays(1)))
                .asBuilder()
                .setCreationTimeForTest(clock.now().minus(Duration.ofDays(2)))
                .build());

    assertThat(cache.loadMostRecentByDomainName("agp.tld")).hasValue(domain);

    verify(jedisClient).get(Domain.class, "agp.tld");
    verify(jedisClient, never()).set(any());
    verify(cacheMetrics).recordLookup("Domain", CacheMetrics.CacheHitType.MISS);
  }

  @Test
  void testLoadMostRecent_softDeleted_pastXapWindow_doesNotPersistToValkey() {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, Tld.ExpiryAccessPeriodMode.ENABLED))
            .build());

    Domain domain = persistDeletedDomain("past-xap.tld", clock.now().minus(Duration.ofDays(11)));

    assertThat(cache.loadMostRecentByDomainName("past-xap.tld")).hasValue(domain);

    verify(jedisClient).get(Domain.class, "past-xap.tld");
    verify(jedisClient, never()).set(any());
    verify(cacheMetrics).recordLookup("Domain", CacheMetrics.CacheHitType.MISS);
  }

  @Test
  void testLoadMostRecent_softDeleted_xapDisabled_doesNotPersistToValkey() {
    Domain domain = persistDeletedDomain("disabled-xap.tld", clock.now().minus(Duration.ofDays(2)));

    assertThat(cache.loadMostRecentByDomainName("disabled-xap.tld")).hasValue(domain);

    verify(jedisClient).get(Domain.class, "disabled-xap.tld");
    verify(jedisClient, never()).set(any());
    verify(cacheMetrics).recordLookup("Domain", CacheMetrics.CacheHitType.MISS);
  }

  @Test
  void testLoadMostRecent_softDeleted_testTld_doesNotPersistToValkey() {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setTldType(Tld.TldType.TEST)
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, Tld.ExpiryAccessPeriodMode.ENABLED))
            .build());

    Domain domain = persistDeletedDomain("test-tld.tld", clock.now().minus(Duration.ofDays(2)));

    assertThat(cache.loadMostRecentByDomainName("test-tld.tld")).hasValue(domain);

    verify(jedisClient).get(Domain.class, "test-tld.tld");
    verify(jedisClient, never()).set(any());
    verify(cacheMetrics).recordLookup("Domain", CacheMetrics.CacheHitType.MISS);
  }

  @Test
  void testShouldPersistToRemoteCache_and_getExpirationTime_boundaries() {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, Tld.ExpiryAccessPeriodMode.ENABLED))
            .build());

    Instant now = clock.now();

    // 1. Active domain
    Domain activeDomain = persistActiveDomain("active.tld");
    assertThat(cache.shouldPersistToRemoteCache(activeDomain)).isTrue();
    assertThat(cache.getExpirationTime(activeDomain)).isEmpty();

    // 2. AGP-deleted domain
    Domain agpDomain =
        persistResource(
            persistDeletedDomain("agp-bound.tld", now.minus(Duration.ofDays(1)))
                .asBuilder()
                .setCreationTimeForTest(now.minus(Duration.ofDays(2)))
                .build());
    assertThat(cache.shouldPersistToRemoteCache(agpDomain)).isFalse();
    assertThat(cache.getExpirationTime(agpDomain)).isEmpty();

    // 3. Boundary: deletionTime == now
    Domain deletedAtNow = persistDeletedDomain("del-now.tld", now);
    assertThat(cache.shouldPersistToRemoteCache(deletedAtNow)).isTrue();
    assertThat(cache.getExpirationTime(deletedAtNow)).hasValue(now.plus(Duration.ofDays(10)));

    // 4. Boundary: deletionTime == now - 10d + 1ms (strictly inside 10d window)
    Instant insideWindow = now.minus(Duration.ofDays(10)).plusMillis(1);
    Domain deletedInsideWindow = persistDeletedDomain("inside.tld", insideWindow);
    assertThat(cache.shouldPersistToRemoteCache(deletedInsideWindow)).isTrue();
    assertThat(cache.getExpirationTime(deletedInsideWindow))
        .hasValue(insideWindow.plus(Duration.ofDays(10)));

    // 5. Boundary: deletionTime == now - 10d (exact edge of 10d window)
    Instant exactEdge = now.minus(Duration.ofDays(10));
    Domain deletedExactEdge = persistDeletedDomain("exact-edge.tld", exactEdge);
    assertThat(cache.shouldPersistToRemoteCache(deletedExactEdge)).isFalse();
    assertThat(cache.getExpirationTime(deletedExactEdge)).isEmpty();

    // 6. Boundary: deletionTime == now - 10d - 1ms (strictly outside window)
    Instant outsideWindow = now.minus(Duration.ofDays(10)).minusMillis(1);
    Domain deletedOutsideWindow = persistDeletedDomain("outside.tld", outsideWindow);
    assertThat(cache.shouldPersistToRemoteCache(deletedOutsideWindow)).isFalse();
    assertThat(cache.getExpirationTime(deletedOutsideWindow)).isEmpty();

    // 7. Custom length constructor (15 days)
    MultilayerDomainCache customCache =
        new MultilayerDomainCache(jedisClient, clock, cacheMetrics, Duration.ofDays(15));
    Instant twelveDaysAgo = now.minus(Duration.ofDays(12));
    Domain deletedTwelveDaysAgo = persistDeletedDomain("twelve-days.tld", twelveDaysAgo);
    // In default 10-day cache: not in XAP
    assertThat(cache.shouldPersistToRemoteCache(deletedTwelveDaysAgo)).isFalse();
    assertThat(cache.getExpirationTime(deletedTwelveDaysAgo)).isEmpty();
    // In custom 15-day cache: in XAP
    assertThat(customCache.shouldPersistToRemoteCache(deletedTwelveDaysAgo)).isTrue();
    assertThat(customCache.getExpirationTime(deletedTwelveDaysAgo))
        .hasValue(twelveDaysAgo.plus(Duration.ofDays(15)));
  }

  @Test
  void testLoadMostRecent_softDeleted_exactAgp_doesNotPersistToValkey() {
    Tld tld =
        persistResource(
            Tld.get("tld")
                .asBuilder()
                .setExpiryAccessPeriodTransitions(
                    ImmutableSortedMap.of(START_INSTANT, Tld.ExpiryAccessPeriodMode.ENABLED))
                .build());

    Instant creationTime = clock.now().minus(Duration.ofDays(6));
    Instant deletionTime = creationTime.plus(tld.getAddGracePeriodLength());
    Domain domain =
        persistResource(
            persistDeletedDomain("agp-exact-cache.tld", deletionTime)
                .asBuilder()
                .setCreationTimeForTest(creationTime)
                .build());

    assertThat(cache.loadMostRecentByDomainName("agp-exact-cache.tld")).hasValue(domain);

    verify(jedisClient).get(Domain.class, "agp-exact-cache.tld");
    verify(jedisClient, never()).set(any());
    verify(cacheMetrics).recordLookup("Domain", CacheMetrics.CacheHitType.MISS);
  }

  @Test
  void testLoadMostRecent_softDeleted_justAfterAgp_persistsToValkey() {
    Tld tld =
        persistResource(
            Tld.get("tld")
                .asBuilder()
                .setExpiryAccessPeriodTransitions(
                    ImmutableSortedMap.of(START_INSTANT, Tld.ExpiryAccessPeriodMode.ENABLED))
                .build());

    Instant creationTime = clock.now().minus(Duration.ofDays(6));
    Instant deletionTime = creationTime.plus(tld.getAddGracePeriodLength()).plusMillis(1);
    Domain domain =
        persistResource(
            persistDeletedDomain("agp-after-cache.tld", deletionTime)
                .asBuilder()
                .setCreationTimeForTest(creationTime)
                .build());

    assertThat(cache.loadMostRecentByDomainName("agp-after-cache.tld")).hasValue(domain);

    verify(jedisClient).get(Domain.class, "agp-after-cache.tld");
    verify(jedisClient)
        .set(
            new SimplifiedJedisClient.JedisResource<>(
                "agp-after-cache.tld", domain, deletionTime.plus(Duration.ofDays(10))));
    verify(cacheMetrics).recordLookup("Domain", CacheMetrics.CacheHitType.MISS);
  }

  @Test
  void testRapidRecreationAndDeletionCycle_transitionsCacheAndTtlCorrectly() {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, Tld.ExpiryAccessPeriodMode.ENABLED))
            .build());

    Instant t0 = clock.now();

    // 1. Initial soft deletion outside AGP (in XAP)
    Instant t1Del = t0.minus(Duration.ofDays(2));
    Domain domainV1 =
        persistResource(
            persistDeletedDomain("cycle.tld", t1Del)
                .asBuilder()
                .setCreationTimeForTest(t0.minus(Duration.ofDays(8)))
                .build());

    MultilayerDomainCache cache1 = new MultilayerDomainCache(jedisClient, clock, cacheMetrics);
    assertThat(cache1.loadMostRecentByDomainName("cycle.tld")).hasValue(domainV1);
    verify(jedisClient)
        .set(
            new SimplifiedJedisClient.JedisResource<>(
                "cycle.tld", domainV1, t1Del.plus(Duration.ofDays(10))));

    // 2. Domain re-registered (active)
    clearInvocations(jedisClient, cacheMetrics);
    Instant t2Create = t0.minus(Duration.ofDays(1));
    Domain domainV2 =
        persistResource(
            persistActiveDomain("cycle.tld")
                .asBuilder()
                .setCreationTimeForTest(t2Create)
                .setDeletionTime(END_INSTANT)
                .build());

    MultilayerDomainCache cache2 = new MultilayerDomainCache(jedisClient, clock, cacheMetrics);
    assertThat(cache2.loadMostRecentByDomainName("cycle.tld")).hasValue(domainV2);
    verify(jedisClient).set(new SimplifiedJedisClient.JedisResource<>("cycle.tld", domainV2));

    // 3. Domain deleted again outside new AGP (new deletionTime & new TTL)
    clearInvocations(jedisClient, cacheMetrics);
    Instant t3Del = t0.plus(Duration.ofDays(6)); // > t2Create + 5d AGP
    clock.setTo(t3Del.plus(Duration.ofDays(1)));
    Domain domainV3 =
        persistResource(
            domainV2.asBuilder().setCreationTimeForTest(t2Create).setDeletionTime(t3Del).build());

    MultilayerDomainCache cache3 = new MultilayerDomainCache(jedisClient, clock, cacheMetrics);
    assertThat(cache3.loadMostRecentByDomainName("cycle.tld")).hasValue(domainV3);
    verify(jedisClient)
        .set(
            new SimplifiedJedisClient.JedisResource<>(
                "cycle.tld", domainV3, t3Del.plus(Duration.ofDays(10))));
  }
}
