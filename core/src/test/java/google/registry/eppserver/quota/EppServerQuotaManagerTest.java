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

package google.registry.eppserver.quota;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import google.registry.config.RegistryConfigSettings.Quota;
import google.registry.config.RegistryConfigSettings.Quota.QuotaGroup;
import google.registry.quota.GenericValkeyQuotaManager;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EppServerQuotaManagerTest {

  @Mock private GenericValkeyQuotaManager quotaManager;

  private Quota quotaConfig;
  private EppServerQuotaManager manager;

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

    manager = new EppServerQuotaManager(quotaConfig, quotaManager);
  }

  @Test
  void testAcquireQuota_defaultQuota() {
    when(quotaManager.acquireQuota("user2", 10, Duration.ofMinutes(1))).thenReturn(true);

    assertThat(manager.acquireQuota("user2")).isTrue();
    verify(quotaManager).acquireQuota("user2", 10, Duration.ofMinutes(1));
  }

  @Test
  void testAcquireQuota_customQuota() {
    when(quotaManager.acquireQuota("user1", 5, Duration.ofSeconds(30))).thenReturn(true);

    assertThat(manager.acquireQuota("user1")).isTrue();
    verify(quotaManager).acquireQuota("user1", 5, Duration.ofSeconds(30));
  }

  @Test
  void testAcquireQuota_unlimited() {
    quotaConfig.defaultQuota.tokenAmount = -1;
    manager = new EppServerQuotaManager(quotaConfig, quotaManager);

    assertThat(manager.acquireQuota("user2")).isTrue();
    verifyNoInteractions(quotaManager);
  }

  @Test
  void testRefreshQuota_success() {
    manager.refreshQuota("user2");
    verify(quotaManager).refreshQuota("user2", Duration.ofMinutes(1));
  }

  @Test
  void testRefreshQuota_unlimited_noop() {
    quotaConfig.defaultQuota.tokenAmount = -1;
    manager = new EppServerQuotaManager(quotaConfig, quotaManager);

    manager.refreshQuota("user2");
    verifyNoInteractions(quotaManager);
  }

  @Test
  void testReleaseQuota_success() {
    manager.releaseQuota("user2");
    verify(quotaManager).releaseQuota("user2", 10);
  }

  @Test
  void testReleaseQuota_unlimited_noop() {
    quotaConfig.defaultQuota.tokenAmount = -1;
    manager = new EppServerQuotaManager(quotaConfig, quotaManager);

    manager.releaseQuota("user2");
    verifyNoInteractions(quotaManager);
  }

  @Test
  void testGroupVirtualIdentity_usesFirstIdInList() {
    // Modify config so "user1" is accompanied by a virtual group ID "my_group"
    quotaConfig.customQuota.get(0).userId = ImmutableList.of("my_group", "user1", "user3");
    manager = new EppServerQuotaManager(quotaConfig, quotaManager);

    when(quotaManager.acquireQuota("my_group", 5, Duration.ofSeconds(30))).thenReturn(true);

    assertThat(manager.acquireQuota("user1")).isTrue();
    assertThat(manager.acquireQuota("user3")).isTrue();
    verify(quotaManager, times(2)).acquireQuota("my_group", 5, Duration.ofSeconds(30));
  }
}
