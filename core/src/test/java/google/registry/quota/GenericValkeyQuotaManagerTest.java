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

package google.registry.quota;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ss_bhatt.testcontainers.valkey.ValkeyContainer;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.RedisClient;

@Testcontainers
class GenericValkeyQuotaManagerTest {

  @Container private static final ValkeyContainer valkey = new ValkeyContainer();

  private RedisClient jedis;
  private GenericValkeyQuotaManager quotaManager;

  @BeforeEach
  void setUp() {
    jedis =
        RedisClient.builder()
            .hostAndPort(new HostAndPort(valkey.getHost(), valkey.getFirstMappedPort()))
            .build();
    jedis.flushAll();
    quotaManager = GenericValkeyQuotaManager.create(jedis, "testQuota");
  }

  @Test
  void testAcquireQuota_success() {
    assertThat(quotaManager.acquireQuota("user1", 5, Duration.ofMinutes(1))).isTrue();
    assertThat(jedis.get("testQuota:user1")).isEqualTo("4");
    assertThat(jedis.ttl("testQuota:user1")).isGreaterThan(0L);
  }

  @Test
  void testAcquireQuota_exhaustsQuota_thenFails() {
    assertThat(quotaManager.acquireQuota("user1", 2, Duration.ofMinutes(1))).isTrue();
    assertThat(jedis.get("testQuota:user1")).isEqualTo("1");

    assertThat(quotaManager.acquireQuota("user1", 2, Duration.ofMinutes(1))).isTrue();
    assertThat(jedis.get("testQuota:user1")).isEqualTo("0");

    assertThat(quotaManager.acquireQuota("user1", 2, Duration.ofMinutes(1))).isFalse();
    assertThat(jedis.get("testQuota:user1")).isEqualTo("0");
  }

  @Test
  void testAcquireQuota_resetsAfterExpiration() throws Exception {
    assertThat(quotaManager.acquireQuota("user1", 1, Duration.ofMillis(50))).isTrue();
    assertThat(quotaManager.acquireQuota("user1", 1, Duration.ofMillis(50))).isFalse();

    Thread.sleep(150);

    assertThat(quotaManager.acquireQuota("user1", 1, Duration.ofMillis(50))).isTrue();
  }

  @Test
  void testAcquireQuota_isolatedByNamespaceAndId() {
    GenericValkeyQuotaManager otherQuotaManager =
        GenericValkeyQuotaManager.create(jedis, "otherQuota");

    assertThat(quotaManager.acquireQuota("user1", 1, Duration.ofMinutes(1))).isTrue();
    assertThat(quotaManager.acquireQuota("user1", 1, Duration.ofMinutes(1))).isFalse();

    // user2 in same namespace is independent
    assertThat(quotaManager.acquireQuota("user2", 1, Duration.ofMinutes(1))).isTrue();

    // user1 in other namespace is independent
    assertThat(otherQuotaManager.acquireQuota("user1", 1, Duration.ofMinutes(1))).isTrue();
  }

  @Test
  void testCreate_nullJedis_throwsNpe() {
    assertThrows(
        NullPointerException.class, () -> GenericValkeyQuotaManager.create(null, "testQuota"));
  }

  @Test
  void testAcquireQuota_jedisException_failsOpen() {
    jedis.close();
    assertThat(quotaManager.acquireQuota("user2", 10, Duration.ofMinutes(1))).isTrue();
  }

  @Test
  void testRefreshQuota_success() {
    quotaManager.acquireQuota("user1", 5, Duration.ofSeconds(10));

    quotaManager.refreshQuota("user1", Duration.ofMinutes(5));
    assertThat(jedis.ttl("testQuota:user1")).isGreaterThan(10L);
  }

  @Test
  void testRefreshQuota_nonexistentKey_noop() {
    quotaManager.refreshQuota("nonexistent", Duration.ofMinutes(5));
    assertThat(jedis.exists("testQuota:nonexistent")).isFalse();
  }

  @Test
  void testRefreshQuota_jedisException_handled() {
    jedis.close();
    assertDoesNotThrow(() -> quotaManager.refreshQuota("user2", Duration.ofMinutes(1)));
  }

  @Test
  void testReleaseQuota_success() {
    assertThat(quotaManager.acquireQuota("user1", 1, Duration.ofMinutes(1))).isTrue();
    assertThat(quotaManager.acquireQuota("user1", 1, Duration.ofMinutes(1))).isFalse();

    quotaManager.releaseQuota("user1", 1);
    assertThat(jedis.get("testQuota:user1")).isEqualTo("1");

    assertThat(quotaManager.acquireQuota("user1", 1, Duration.ofMinutes(1))).isTrue();
  }

  @Test
  void testReleaseQuota_cappedAtMax() {
    quotaManager.acquireQuota("user1", 3, Duration.ofMinutes(1));
    assertThat(jedis.get("testQuota:user1")).isEqualTo("2");

    quotaManager.releaseQuota("user1", 3);
    assertThat(jedis.get("testQuota:user1")).isEqualTo("3");

    // Releasing again when already at max should not increment past max
    quotaManager.releaseQuota("user1", 3);
    assertThat(jedis.get("testQuota:user1")).isEqualTo("3");
  }

  @Test
  void testReleaseQuota_nonexistentKey_noop() {
    quotaManager.releaseQuota("nonexistent", 5);
    assertThat(jedis.exists("testQuota:nonexistent")).isFalse();
  }

  @Test
  void testReleaseQuota_jedisException_handled() {
    jedis.close();
    assertDoesNotThrow(() -> quotaManager.releaseQuota("user2", 10));
  }
}
