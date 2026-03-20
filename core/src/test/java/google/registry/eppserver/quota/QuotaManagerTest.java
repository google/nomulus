// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.eppserver.quota;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import google.registry.config.RegistryConfigSettings.Quota;
import google.registry.config.RegistryConfigSettings.Quota.QuotaGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.UnifiedJedis;

@ExtendWith(MockitoExtension.class)
class QuotaManagerTest {

  @Mock private UnifiedJedis jedis;

  private Quota quotaConfig;
  private QuotaManager manager;

  @BeforeEach
  void setUp() {
    quotaConfig = new Quota();
    QuotaGroup defaultGroup = new QuotaGroup();
    defaultGroup.tokenAmount = 10;
    defaultGroup.refillSeconds = 60;
    quotaConfig.defaultQuota = defaultGroup;

    QuotaGroup customGroup = new QuotaGroup();
    customGroup.tokenAmount = 5;
    customGroup.refillSeconds = 30;
    customGroup.userId = ImmutableList.of("user1");
    quotaConfig.customQuota = ImmutableList.of(customGroup);

    manager = new QuotaManager(quotaConfig, jedis, "testQuota");
  }

  @Test
  void testAcquireQuota_success() {
    when(jedis.eval(anyString(), anyInt(), anyString(), anyString(), anyString())).thenReturn(5L);

    QuotaManager.QuotaResponse response =
        manager.acquireQuota(new QuotaManager.QuotaRequest("user2"));
    assertThat(response.success()).isTrue();
    verify(jedis).eval(anyString(), eq(1), eq("testQuota:user2"), eq("10"), eq("60"));
  }

  @Test
  void testAcquireQuota_failure() {
    when(jedis.eval(anyString(), anyInt(), anyString(), anyString(), anyString())).thenReturn(-1L);

    QuotaManager.QuotaResponse response =
        manager.acquireQuota(new QuotaManager.QuotaRequest("user1"));
    assertThat(response.success()).isFalse();
    verify(jedis).eval(anyString(), eq(1), eq("testQuota:user1"), eq("5"), eq("30"));
  }

  @Test
  void testAcquireQuota_unlimited() {
    quotaConfig.defaultQuota.tokenAmount = -1;
    manager = new QuotaManager(quotaConfig, jedis, "testQuota");

    QuotaManager.QuotaResponse response =
        manager.acquireQuota(new QuotaManager.QuotaRequest("user2"));
    assertThat(response.success()).isTrue();
  }

  @Test
  void testAcquireQuota_jedisException_failsOpen() {
    when(jedis.eval(anyString(), anyInt(), anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException("Redis error"));

    QuotaManager.QuotaResponse response =
        manager.acquireQuota(new QuotaManager.QuotaRequest("user2"));
    assertThat(response.success()).isTrue();
  }

  @Test
  void testRefreshQuota_success() {
    manager.refreshQuota(new QuotaManager.QuotaRequest("user2"));
    verify(jedis).eval(anyString(), eq(1), eq("testQuota:user2"), eq("60"));
  }

  @Test
  void testReleaseQuota_success() {
    manager.releaseQuota(new QuotaManager.QuotaRebate("user2"));
    verify(jedis).eval(anyString(), eq(1), eq("testQuota:user2"), eq("10"));
  }

  @Test
  void testGroupVirtualIdentity_usesFirstIdInList() {
    // Modify config so "user1" is accompanied by a virtual group ID "my_group"
    quotaConfig.customQuota.get(0).userId = ImmutableList.of("my_group", "user1", "user3");
    manager = new QuotaManager(quotaConfig, jedis, "testQuota");

    when(jedis.eval(anyString(), anyInt(), anyString(), anyString(), anyString())).thenReturn(5L);

    QuotaManager.QuotaResponse response1 =
        manager.acquireQuota(new QuotaManager.QuotaRequest("user1"));
    QuotaManager.QuotaResponse response2 =
        manager.acquireQuota(new QuotaManager.QuotaRequest("user3"));

    assertThat(response1.success()).isTrue();
    assertThat(response2.success()).isTrue();
    // 5 tokens, 30 seconds ttl
    verify(jedis, times(2)).eval(anyString(), eq(1), eq("testQuota:my_group"), eq("5"), eq("30"));
  }

  @Test
  void testGroupVirtualIdentity_exceedsQuota_fails() {
    // Modify config so "user1", "user2", "user3" share virtual group ID "my_group"
    quotaConfig.customQuota.get(0).userId = ImmutableList.of("my_group", "user1", "user2", "user3");
    manager = new QuotaManager(quotaConfig, jedis, "testQuota");

    // Simulate Redis returning 1, 0 for successful decrements, and -1 when empty
    when(jedis.eval(anyString(), anyInt(), anyString(), anyString(), anyString()))
        .thenReturn(1L)
        .thenReturn(0L)
        .thenReturn(-1L);

    // Act
    QuotaManager.QuotaResponse response1 =
        manager.acquireQuota(new QuotaManager.QuotaRequest("user1"));
    QuotaManager.QuotaResponse response2 =
        manager.acquireQuota(new QuotaManager.QuotaRequest("user2"));
    QuotaManager.QuotaResponse response3 =
        manager.acquireQuota(new QuotaManager.QuotaRequest("user3"));

    // Assert that the third request to the same group fails
    assertThat(response1.success()).isTrue();
    assertThat(response2.success()).isTrue();
    assertThat(response3.success()).isFalse();

    // Verify all 3 requests went to the shared bucket
    verify(jedis, times(3)).eval(anyString(), eq(1), eq("testQuota:my_group"), eq("5"), eq("30"));
  }
}
