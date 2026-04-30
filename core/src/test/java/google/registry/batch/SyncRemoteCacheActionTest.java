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
import static google.registry.model.common.Cursor.CursorType.REMOTE_CACHE_DOMAIN_SYNC;
import static google.registry.model.common.Cursor.CursorType.REMOTE_CACHE_HOST_SYNC;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedHost;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.common.collect.ImmutableList;
import google.registry.cache.SimplifiedJedisClient;
import google.registry.model.common.Cursor;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeLockHandler;
import google.registry.testing.FakeResponse;
import java.time.Instant;
import java.util.Optional;
import org.joda.time.DateTime;
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

  private final FakeClock clock = new FakeClock(DateTime.parse("2025-01-01T00:00:00Z"));

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @Mock private SimplifiedJedisClient<Domain> domainJedisClient;
  @Mock private SimplifiedJedisClient<Host> hostJedisClient;

  private final FakeResponse response = new FakeResponse();
  private FakeLockHandler lockHandler = new FakeLockHandler(true);
  private SyncRemoteCacheAction action;

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    action =
        new SyncRemoteCacheAction(
            lockHandler, response, Optional.of(domainJedisClient), Optional.of(hostJedisClient));
  }

  @Test
  void test_noJedisConfig() {
    action = new SyncRemoteCacheAction(lockHandler, response, Optional.empty(), Optional.empty());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload()).contains("No Jedis/Valkey configuration found");
  }

  @Test
  void test_lockAcquisitionFails() {
    lockHandler = new FakeLockHandler(false);
    action =
        new SyncRemoteCacheAction(
            lockHandler, response, Optional.of(domainJedisClient), Optional.of(hostJedisClient));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload()).contains("Could not acquire lock");
  }

  @Test
  void test_exceptionThrown() {
    doThrow(new RuntimeException("Redis failed")).when(domainJedisClient).deleteAll(any());
    persistActiveDomain("example.tld"); // So there is something to process
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    assertThat(response.getPayload()).contains("Errored out with cause");
  }

  @Test
  void test_syncDomains_noDomains() {
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verifyNoInteractions(domainJedisClient);
    assertThat(DatabaseHelper.loadByKeyIfPresent(Cursor.createGlobalVKey(REMOTE_CACHE_DOMAIN_SYNC)))
        .isEmpty();
  }

  @Test
  void test_syncDomains_withDomains() {
    Domain domain1 = persistActiveDomain("example1.tld");
    clock.advanceOneMilli();
    Domain domain2 = persistActiveDomain("example2.tld");

    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(domainJedisClient)
        .setAll(
            eq(
                ImmutableList.of(
                    new SimplifiedJedisClient.JedisResource<>("example1.tld", domain1),
                    new SimplifiedJedisClient.JedisResource<>("example2.tld", domain2))));

    assertThat(
            DatabaseHelper.loadByKey(Cursor.createGlobalVKey(REMOTE_CACHE_DOMAIN_SYNC))
                .getCursorTime()
                .toString())
        .isEqualTo("2025-01-01T00:00:00.001Z");
  }

  @Test
  void test_syncDomains_withDeletedDomains() {
    Domain activeDomain = persistActiveDomain("active.tld");
    persistDeletedDomain("deleted.tld", clock.nowUtc().minusDays(1));

    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(domainJedisClient)
        .setAll(
            eq(
                ImmutableList.of(
                    new SimplifiedJedisClient.JedisResource<>("active.tld", activeDomain))));
    verify(domainJedisClient).deleteAll(eq(ImmutableList.of("deleted.tld")));
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
    verify(domainJedisClient)
        .setAll(
            eq(
                ImmutableList.of(
                    new SimplifiedJedisClient.JedisResource<>("example2.tld", domain2))));
  }

  @Test
  void test_syncHosts_noHosts() {
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verifyNoInteractions(hostJedisClient);
    assertThat(DatabaseHelper.loadByKeyIfPresent(Cursor.createGlobalVKey(REMOTE_CACHE_HOST_SYNC)))
        .isEmpty();
  }

  @Test
  void test_syncHosts_withHosts() {
    Host host1 = persistActiveHost("ns1.example.tld");
    clock.advanceOneMilli();
    Host host2 = persistActiveHost("ns2.example.tld");

    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(hostJedisClient)
        .setAll(
            eq(
                ImmutableList.of(
                    new SimplifiedJedisClient.JedisResource<>(host1.getRepoId(), host1),
                    new SimplifiedJedisClient.JedisResource<>(host2.getRepoId(), host2))));

    assertThat(
            DatabaseHelper.loadByKey(Cursor.createGlobalVKey(REMOTE_CACHE_HOST_SYNC))
                .getCursorTime()
                .toString())
        .isEqualTo("2025-01-01T00:00:00.001Z");
  }

  @Test
  void test_syncHosts_withDeletedHosts() {
    Host active = persistActiveHost("ns1.example.tld");
    Host deleted = persistDeletedHost("ns2.example.tld", clock.nowUtc().minusDays(1));

    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(hostJedisClient)
        .setAll(
            eq(
                ImmutableList.of(
                    new SimplifiedJedisClient.JedisResource<>(active.getRepoId(), active))));
    verify(hostJedisClient).deleteAll(eq(ImmutableList.of(deleted.getRepoId())));
  }
}
