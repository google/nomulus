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

package google.registry.proxy.quota;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import google.registry.proxy.ProxyConfig.Quota;
import google.registry.proxy.ProxyConfig.Quota.QuotaGroup;
import org.joda.time.Duration;

/** Value class that stores the quota configuration for a protocol. */
public class QuotaConfig {

  /** A special value of token amount that indicates unlimited tokens. */
  public static final int SENTINEL_UNLIMITED_TOKENS = -1;

  private final String protocolName;
  private final int refreshSeconds;
  private final QuotaGroup defaultQuota;
  private final ImmutableMap<String, QuotaGroup> customQuotaMap;

  /**
   * Constructs a {@link QuotaConfig} from a {@link Quota}.
   *
   * <p>Each {@link QuotaGroup} is keyed to all the {@code userId}s it contains. This allows for
   * fast lookup with a {@code userId}.
   */
  public QuotaConfig(Quota quota, String protocolName) {
    this.protocolName = protocolName;
    refreshSeconds = quota.refreshSeconds;
    defaultQuota = quota.defaultQuota;
    ImmutableMap.Builder<String, QuotaGroup> mapBuilder = new ImmutableMap.Builder<>();
    quota.customQuota.forEach(
        quotaGroup -> quotaGroup.userId.forEach(userId -> mapBuilder.put(userId, quotaGroup)));
    customQuotaMap = mapBuilder.build();
  }

  @VisibleForTesting
  QuotaGroup findQuotaGroup(String userId) {
    return customQuotaMap.getOrDefault(userId, defaultQuota);
  }

  /**
   * Returns if the given user ID is provisioned with unlimited tokens.
   *
   * <p>This is configured by setting {@code tokenAmount} to {@code -1} in the config file.
   */
  boolean hasUnlimitedTokens(String userId) {
    return findQuotaGroup(userId).tokenAmount == SENTINEL_UNLIMITED_TOKENS;
  }

  /** Returns the token amount for the given {@code userId}. */
  int getTokenAmount(String userId) {
    checkState(
        !hasUnlimitedTokens(userId), "User ID %s is provisioned with unlimited tokens", userId);
    return findQuotaGroup(userId).tokenAmount;
  }

  /** Returns the refill period for the given {@code userId}. */
  Duration getRefillPeriod(String userId) {
    return Duration.standardSeconds(findQuotaGroup(userId).refillSeconds);
  }

  /** Returns the refresh period for this quota config. */
  Duration getRefreshPeriod() {
    return Duration.standardSeconds(refreshSeconds);
  }

  /** Returns the name of the protocol for which this quota config is made. */
  String getProtocolName() {
    return protocolName;
  }
}
