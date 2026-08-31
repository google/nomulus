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

package google.registry.batch;

import static com.google.common.truth.Truth.assertThat;
import static com.google.monitoring.metrics.contrib.LongMetricSubject.assertThat;
import static google.registry.batch.SyncRemoteCacheAction.SyncStatus.FAILURE;
import static google.registry.batch.SyncRemoteCacheAction.SyncStatus.NOT_CONFIGURED;
import static google.registry.batch.SyncRemoteCacheAction.SyncStatus.SUCCESS;
import static google.registry.model.common.Cursor.CursorType.REMOTE_CACHE_DOMAIN_SYNC;
import static google.registry.model.common.Cursor.CursorType.REMOTE_CACHE_HOST_SYNC;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedHost;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_INSTANT;
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static google.registry.util.DateTimeUtils.minusDays;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.cache.SimplifiedJedisClient;
import google.registry.model.common.Cursor;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.ExpiryAccessPeriodMode;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeLockHandler;
import google.registry.testing.FakeResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link SyncRemoteCacheAction}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SyncRemoteCacheActionTest {

  private final FakeClock clock = new FakeClock(Instant.parse("2025-01-01T00:00:00Z"));

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @Mock private SimplifiedJedisClient jedisClient;

  private final FakeResponse response = new FakeResponse();
  private FakeLockHandler lockHandler = new FakeLockHandler(true);
  private SyncRemoteCacheAction action;

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    SyncRemoteCacheAction.SYNC_CACHE_RUNS_METRIC.reset();
    action = new SyncRemoteCacheAction(lockHandler, response, Optional.of(jedisClient));
  }

  private static void verifyMetrics(SyncRemoteCacheAction.SyncStatus status) {
    assertThat(SyncRemoteCacheAction.SYNC_CACHE_RUNS_METRIC)
        .hasValueForLabels(1, status.name())
        .and()
        .hasNoOtherValues();
  }

  @Test
  void test_noJedisConfig() {
    action = new SyncRemoteCacheAction(lockHandler, response, Optional.empty());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload()).contains("No Jedis/Valkey configuration found");
    verifyMetrics(NOT_CONFIGURED);
  }

  @Test
  void test_lockAcquisitionFails() {
    lockHandler = new FakeLockHandler(false);
    action = new SyncRemoteCacheAction(lockHandler, response, Optional.of(jedisClient));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload()).contains("Could not acquire lock");
    verifyMetrics(FAILURE);
  }

  @Test
  void test_exceptionThrown() {
    doThrow(new RuntimeException("Redis failed")).when(jedisClient).deleteAll(any(), any());
    persistActiveDomain("example.tld"); // So there is something to process
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    assertThat(response.getPayload()).contains("Errored out with cause");
    verifyMetrics(FAILURE);
  }

  @Test
  void test_syncDomains_noDomains() {
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verifyNoInteractions(jedisClient);
    assertThat(DatabaseHelper.loadByKeyIfPresent(Cursor.createGlobalVKey(REMOTE_CACHE_DOMAIN_SYNC)))
        .isEmpty();
    verifyMetrics(SUCCESS);
  }

  @Test
  void test_syncDomains_withDomains() {
    Domain domain1 = persistActiveDomain("example1.tld");
    clock.advanceOneMilli();
    Domain domain2 = persistActiveDomain("example2.tld");

    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(jedisClient)
        .setAll(
            ImmutableList.of(
                new SimplifiedJedisClient.JedisResource<>("example1.tld", domain1),
                new SimplifiedJedisClient.JedisResource<>("example2.tld", domain2)));

    assertThat(
            DatabaseHelper.loadByKey(Cursor.createGlobalVKey(REMOTE_CACHE_DOMAIN_SYNC))
                .getCursorTime()
                .toString())
        .isEqualTo("2025-01-01T00:00:00.001Z");
    verifyMetrics(SUCCESS);
  }

  @Test
  void test_syncDomains_withDeletedDomains() {
    Domain activeDomain = persistActiveDomain("active.tld");
    persistDeletedDomain("deleted.tld", minusDays(clock.now(), 1));

    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(jedisClient)
        .setAll(
            ImmutableList.of(
                new SimplifiedJedisClient.JedisResource<>("active.tld", activeDomain)));
    verify(jedisClient).deleteAll(Domain.class, ImmutableList.of("deleted.tld"));
    verifyMetrics(SUCCESS);
  }

  @Test
  void test_syncDomains_withXapEnabled_keepsDeletedDomainInRemoteCache() {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    Domain activeDomain = persistActiveDomain("active.tld");
    Domain xapDomain = persistDeletedDomain("xap.tld", minusDays(clock.now(), 1));

    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(jedisClient)
        .setAll(
            ImmutableList.of(
                new SimplifiedJedisClient.JedisResource<>("active.tld", activeDomain),
                new SimplifiedJedisClient.JedisResource<>(
                    "xap.tld", xapDomain, xapDomain.getDeletionTime().plus(Duration.ofDays(10)))));
    verify(jedisClient).deleteAll(Domain.class, ImmutableList.of());
    verifyMetrics(SUCCESS);
  }

  @Test
  void test_syncDomains_withXapEnabled_deletedDuringAgp_deletedFromRemoteCache() {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    Tld tld = Tld.get("tld");
    persistResource(
        persistActiveDomain("agp.tld")
            .asBuilder()
            .setCreationTimeForTest(clock.now().minus(tld.getAddGracePeriodLength()))
            .setDeletionTime(clock.now())
            .build());

    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(jedisClient).setAll(ImmutableList.of());
    verify(jedisClient).deleteAll(Domain.class, ImmutableList.of("agp.tld"));
    verifyMetrics(SUCCESS);
  }

  @Test
  void test_syncDomains_withXapEnabled_atDeletionTime_keepsDomainInRemoteCache() {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    Domain xapDomain = persistDeletedDomain("xap-now.tld", clock.now());

    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(jedisClient)
        .setAll(
            ImmutableList.of(
                new SimplifiedJedisClient.JedisResource<>(
                    "xap-now.tld",
                    xapDomain,
                    xapDomain.getDeletionTime().plus(Duration.ofDays(10)))));
    verify(jedisClient).deleteAll(Domain.class, ImmutableList.of());
    verifyMetrics(SUCCESS);
  }

  @Test
  void test_syncDomains_withXapEnabled_atExactExpiry_deletedFromRemoteCache() {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    persistDeletedDomain("exact-expiry.tld", minusDays(clock.now(), 10));

    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(jedisClient).setAll(ImmutableList.of());
    verify(jedisClient).deleteAll(Domain.class, ImmutableList.of("exact-expiry.tld"));
    verifyMetrics(SUCCESS);
  }

  @Test
  void test_syncDomains_withXapEnabled_insideExpiry_keepsDomainInRemoteCache() {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    Domain xapDomain =
        persistDeletedDomain(
            "inside-expiry.tld", clock.now().minus(Duration.ofDays(10)).plusMillis(1));

    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(jedisClient)
        .setAll(
            ImmutableList.of(
                new SimplifiedJedisClient.JedisResource<>(
                    "inside-expiry.tld",
                    xapDomain,
                    xapDomain.getDeletionTime().plus(Duration.ofDays(10)))));
    verify(jedisClient).deleteAll(Domain.class, ImmutableList.of());
    verifyMetrics(SUCCESS);
  }

  @Test
  void test_syncDomains_withXapDisabled_deletedDomainDeletedFromRemoteCache() {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.DISABLED))
            .build());
    persistDeletedDomain("xap-disabled.tld", minusDays(clock.now(), 1));

    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(jedisClient).setAll(ImmutableList.of());
    verify(jedisClient).deleteAll(Domain.class, ImmutableList.of("xap-disabled.tld"));
    verifyMetrics(SUCCESS);
  }

  @Test
  void test_syncDomains_cursorAdvances_skipsUnchangedExpiredXapDomain() {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());

    Instant t0 = clock.now();
    Domain xapDomain = persistDeletedDomain("xap.tld", minusDays(t0, 1));

    // Run 1: Initial synchronization at T0
    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(jedisClient)
        .setAll(
            ImmutableList.of(
                new SimplifiedJedisClient.JedisResource<>(
                    "xap.tld", xapDomain, xapDomain.getDeletionTime().plus(Duration.ofDays(10)))));
    Cursor cursor = DatabaseHelper.loadByKey(Cursor.createGlobalVKey(REMOTE_CACHE_DOMAIN_SYNC));
    assertThat(cursor.getCursorTime()).isEqualTo(t0);

    // Run 2: Advance clock past 10d XAP window
    clock.advanceBy(Duration.ofDays(12));
    clearInvocations(jedisClient);
    FakeResponse response2 = new FakeResponse();
    action = new SyncRemoteCacheAction(lockHandler, response2, Optional.of(jedisClient));

    action.run();

    assertThat(response2.getStatus()).isEqualTo(SC_OK);
    assertThat(response2.getPayload()).contains("Synced 0 domains");
    verifyNoInteractions(jedisClient);
    assertThat(
            DatabaseHelper.loadByKey(Cursor.createGlobalVKey(REMOTE_CACHE_DOMAIN_SYNC))
                .getCursorTime())
        .isEqualTo(t0);

    // Run 3: Subsequent mutation at new time
    clock.advanceOneMilli();
    Domain newActive = persistActiveDomain("newactive.tld");
    FakeResponse response3 = new FakeResponse();
    action = new SyncRemoteCacheAction(lockHandler, response3, Optional.of(jedisClient));

    action.run();

    assertThat(response3.getStatus()).isEqualTo(SC_OK);
    assertThat(response3.getPayload()).contains("Synced 1 domains");
    verify(jedisClient)
        .setAll(
            ImmutableList.of(
                new SimplifiedJedisClient.JedisResource<>("newactive.tld", newActive)));
    assertThat(
            DatabaseHelper.loadByKey(Cursor.createGlobalVKey(REMOTE_CACHE_DOMAIN_SYNC))
                .getCursorTime())
        .isEqualTo(clock.now());
  }

  @Test
  void test_syncDomains_withXapEnabled_deletesDomainDeletedOutsideWindow() {
    DatabaseHelper.persistResource(
        Tld.get("tld")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    Domain activeDomain = persistActiveDomain("active.tld");
    persistDeletedDomain("expired.tld", minusDays(clock.now(), 15));

    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(jedisClient)
        .setAll(
            ImmutableList.of(
                new SimplifiedJedisClient.JedisResource<>("active.tld", activeDomain)));
    verify(jedisClient).deleteAll(Domain.class, ImmutableList.of("expired.tld"));
    verifyMetrics(SUCCESS);
  }

  @Test
  void testCursorTime_skipsOldChange() {
    persistActiveDomain("example1.tld");

    clock.advanceOneMilli();
    Instant cursorTime = clock.now();

    DatabaseHelper.persistResource(Cursor.createGlobal(REMOTE_CACHE_DOMAIN_SYNC, cursorTime));

    clock.advanceOneMilli();
    Domain domain2 = persistActiveDomain("example2.tld");

    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(jedisClient)
        .setAll(
            ImmutableList.of(new SimplifiedJedisClient.JedisResource<>("example2.tld", domain2)));
    verifyMetrics(SUCCESS);
  }

  @Test
  void test_syncHosts_noHosts() {
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verifyNoInteractions(jedisClient);
    assertThat(DatabaseHelper.loadByKeyIfPresent(Cursor.createGlobalVKey(REMOTE_CACHE_HOST_SYNC)))
        .isEmpty();
    verifyMetrics(SUCCESS);
  }

  @Test
  void test_syncHosts_withHosts() {
    Host host1 = persistActiveHost("ns1.example.tld");
    clock.advanceOneMilli();
    Host host2 = persistActiveHost("ns2.example.tld");

    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(jedisClient)
        .setAll(
            ImmutableList.of(
                new SimplifiedJedisClient.JedisResource<>(host1.getRepoId(), host1),
                new SimplifiedJedisClient.JedisResource<>(host2.getRepoId(), host2)));

    assertThat(
            DatabaseHelper.loadByKey(Cursor.createGlobalVKey(REMOTE_CACHE_HOST_SYNC))
                .getCursorTime()
                .toString())
        .isEqualTo("2025-01-01T00:00:00.001Z");
    verifyMetrics(SUCCESS);
  }

  @Test
  void test_syncHosts_withDeletedHosts() {
    Host active = persistActiveHost("ns1.example.tld");
    Host deleted = persistDeletedHost("ns2.example.tld", minusDays(clock.now(), 1));

    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(jedisClient)
        .setAll(
            ImmutableList.of(
                new SimplifiedJedisClient.JedisResource<>(active.getRepoId(), active)));
    verify(jedisClient).deleteAll(Host.class, ImmutableList.of(deleted.getRepoId()));
    verifyMetrics(SUCCESS);
  }

  @Test
  void test_syncDomains_withXapEnabled_deletedJustAfterAgp_retainedInCache() {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    Tld tld = Tld.get("tld");
    Domain xapDomain =
        persistResource(
            persistActiveDomain("agp-outside.tld")
                .asBuilder()
                .setCreationTimeForTest(
                    clock.now().minus(tld.getAddGracePeriodLength()).minusMillis(1))
                .setDeletionTime(clock.now())
                .build());

    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(jedisClient)
        .setAll(
            ImmutableList.of(
                new SimplifiedJedisClient.JedisResource<>(
                    "agp-outside.tld",
                    xapDomain,
                    xapDomain.getDeletionTime().plus(Duration.ofDays(10)))));
    verify(jedisClient).deleteAll(Domain.class, ImmutableList.of());
    verifyMetrics(SUCCESS);
  }

  @Test
  void test_syncDomains_rapidRecreationAndDeletion_transitionsCacheState() {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());

    Instant t0 = clock.now();

    // Run 1: Deleted in XAP at t0 - 1d (creation t0 - 7d)
    Instant t1Del = t0.minus(Duration.ofDays(1));
    Domain v1 =
        persistResource(
            persistActiveDomain("cycle-sync.tld")
                .asBuilder()
                .setCreationTimeForTest(t0.minus(Duration.ofDays(7)))
                .setDeletionTime(t1Del)
                .build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(jedisClient)
        .setAll(
            ImmutableList.of(
                new SimplifiedJedisClient.JedisResource<>(
                    "cycle-sync.tld", v1, t1Del.plus(Duration.ofDays(10)))));
    verify(jedisClient).deleteAll(Domain.class, ImmutableList.of());

    // Run 2: Re-registered at t0 + 2d (active)
    clock.advanceBy(Duration.ofDays(2));
    clearInvocations(jedisClient);
    Instant t2Create = clock.now();
    Domain v2 =
        persistResource(
            v1.asBuilder().setCreationTimeForTest(t2Create).setDeletionTime(END_INSTANT).build());
    FakeResponse resp2 = new FakeResponse();
    new SyncRemoteCacheAction(lockHandler, resp2, Optional.of(jedisClient)).run();
    assertThat(resp2.getStatus()).isEqualTo(SC_OK);
    verify(jedisClient)
        .setAll(ImmutableList.of(new SimplifiedJedisClient.JedisResource<>("cycle-sync.tld", v2)));
    verify(jedisClient).deleteAll(Domain.class, ImmutableList.of());

    // Run 3: Deleted again at t2Create + 6d (outside 5d AGP) -> new TTL
    clock.advanceBy(Duration.ofDays(6));
    clearInvocations(jedisClient);
    Instant t3Del = clock.now();
    Domain v3 = persistResource(v2.asBuilder().setDeletionTime(t3Del).build());
    FakeResponse resp3 = new FakeResponse();
    new SyncRemoteCacheAction(lockHandler, resp3, Optional.of(jedisClient)).run();
    assertThat(resp3.getStatus()).isEqualTo(SC_OK);
    verify(jedisClient)
        .setAll(
            ImmutableList.of(
                new SimplifiedJedisClient.JedisResource<>(
                    "cycle-sync.tld", v3, t3Del.plus(Duration.ofDays(10)))));
    verify(jedisClient).deleteAll(Domain.class, ImmutableList.of());

    // Run 4: Re-registered at t3Del + 1d and deleted inside AGP -> purged
    clock.advanceBy(Duration.ofDays(1));
    clearInvocations(jedisClient);
    Instant t4Create = clock.now();
    Instant t4Del = t4Create.plus(Duration.ofDays(1)); // inside 5d AGP
    clock.setTo(t4Del);
    persistResource(v3.asBuilder().setCreationTimeForTest(t4Create).setDeletionTime(t4Del).build());
    FakeResponse resp4 = new FakeResponse();
    new SyncRemoteCacheAction(lockHandler, resp4, Optional.of(jedisClient)).run();
    assertThat(resp4.getStatus()).isEqualTo(SC_OK);
    verify(jedisClient).setAll(ImmutableList.of());
    verify(jedisClient).deleteAll(Domain.class, ImmutableList.of("cycle-sync.tld"));
  }
}
