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

import com.google.common.collect.ImmutableMap;
import google.registry.config.RegistryConfigSettings.Quota;
import google.registry.config.RegistryConfigSettings.Quota.QuotaGroup;
import google.registry.quota.GenericValkeyQuotaManager;
import java.time.Duration;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Quota management for the EPP server using Redis/Valkey.
 *
 * <p>Handles primarily configuration lookup and delegation to the generic quota manager.
 */
@ThreadSafe
public class EppServerQuotaManager {

  private static final Duration DEFAULT_TTL = Duration.ofHours(1);

  private final GenericValkeyQuotaManager quotaManager;
  private final QuotaGroup defaultQuota;
  private final ImmutableMap<String, QuotaGroup> customQuotas;

  public EppServerQuotaManager(Quota quota, GenericValkeyQuotaManager quotaManager) {
    this.quotaManager = quotaManager;
    this.defaultQuota = quota.defaultQuota;

    ImmutableMap.Builder<String, QuotaGroup> builder = ImmutableMap.builder();
    quota.customQuota.forEach(group -> group.userId.forEach(userId -> builder.put(userId, group)));
    this.customQuotas = builder.build();
  }

  /** Attempts to acquire a quota token from Redis. */
  public boolean acquireQuota(String userId) {
    QuotaGroup group = customQuotas.getOrDefault(userId, defaultQuota);

    // Unlimited quota check
    if (group.tokenAmount < 0) {
      return true;
    }

    String redisId = getRedisId(group, userId);
    return quotaManager.acquireQuota(redisId, group.tokenAmount, getTtl(group));
  }

  /** Refreshes the TTL of an existing quota token. */
  public void refreshQuota(String userId) {
    QuotaGroup group = customQuotas.getOrDefault(userId, defaultQuota);
    if (group.tokenAmount < 0) {
      return;
    }

    String redisId = getRedisId(group, userId);
    quotaManager.refreshQuota(redisId, getTtl(group));
  }

  /** Returns a token to the pool (used for connection throttling). */
  public void releaseQuota(String userId) {
    QuotaGroup group = customQuotas.getOrDefault(userId, defaultQuota);
    if (group.tokenAmount < 0) {
      return;
    }

    String redisId = getRedisId(group, userId);
    quotaManager.releaseQuota(redisId, group.tokenAmount);
  }

  private String getRedisId(QuotaGroup group, String userId) {
    // Use the first ID as the virtual group identity if it's a custom group,
    // otherwise isolate each default user by their actual ID.
    return (group == defaultQuota || group.userId.isEmpty()) ? userId : group.userId.get(0);
  }

  private Duration getTtl(QuotaGroup group) {
    return group.refillSeconds > 0 ? Duration.ofSeconds(group.refillSeconds) : DEFAULT_TTL;
  }
}
