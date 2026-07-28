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

import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfigSettings.Quota;
import google.registry.config.RegistryConfigSettings.Quota.QuotaGroup;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import redis.clients.jedis.UnifiedJedis;

/**
 * Unified manager for distributed quota enforcement using Redis/Valkey.
 *
 * <p>Handles both configuration lookup and atomic Redis operations for connection and command-level
 * throttling.
 */
@ThreadSafe
public class QuotaManager {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final int DEFAULT_TTL_SECONDS = 3600;

  /** Lua script to atomically decrement a token bucket with a TTL. */
  private static final String DECR_LUA =
      "local current = redis.call('GET', KEYS[1]) "
          + "if not current then "
          + "  redis.call('SET', KEYS[1], ARGV[1] - 1, 'EX', ARGV[2]) "
          + "  return tonumber(ARGV[1]) - 1 "
          + "end "
          + "if tonumber(current) <= 0 then "
          + "  return -1 "
          + "end "
          + "return redis.call('DECR', KEYS[1])";

  /** Lua script to atomically increment back a connection token (capped at max). */
  private static final String INCR_LUA =
      "local current = redis.call('GET', KEYS[1]) "
          + "if current and tonumber(current) < tonumber(ARGV[1]) then "
          + "  return redis.call('INCR', KEYS[1]) "
          + "end "
          + "return nil";

  /** Lua script to refresh the TTL of an existing token bucket. */
  private static final String EXPIRE_LUA =
      "if redis.call('EXISTS', KEYS[1]) == 1 then "
          + "  return redis.call('EXPIRE', KEYS[1], ARGV[1]) "
          + "end "
          + "return 0";

  private final UnifiedJedis jedis;
  private final String quotaNamespace;
  private final QuotaGroup defaultQuota;
  private final ImmutableMap<String, QuotaGroup> customQuotas;

  public QuotaManager(Quota quota, @Nullable UnifiedJedis jedis, String quotaNamespace) {
    this.jedis = jedis;
    this.quotaNamespace = quotaNamespace;
    this.defaultQuota = quota.defaultQuota;

    ImmutableMap.Builder<String, QuotaGroup> builder = ImmutableMap.builder();
    quota.customQuota.forEach(group -> group.userId.forEach(userId -> builder.put(userId, group)));
    this.customQuotas = builder.build();
  }

  public record QuotaRequest(String userId) {}

  public record QuotaResponse(boolean success) {}

  public record QuotaRebate(String userId) {}

  /** Attempts to acquire a quota token from Redis. */
  public QuotaResponse acquireQuota(QuotaRequest request) {
    String userId = request.userId();
    QuotaGroup group = customQuotas.getOrDefault(userId, defaultQuota);

    // Unlimited quota check
    if (group.tokenAmount < 0) {
      return new QuotaResponse(true);
    }

    if (jedis == null) {
      return new QuotaResponse(true); // Fail open if no Valkey configured
    }

    // Use the first ID as the virtual group identity if it's a custom group,
    // otherwise isolate each default user by their actual ID.
    String redisId =
        (group == defaultQuota || group.userId.isEmpty()) ? userId : group.userId.get(0);
    String key = String.format("%s:%s", quotaNamespace, redisId);
    int ttl = group.refillSeconds > 0 ? group.refillSeconds : DEFAULT_TTL_SECONDS;

    try {
      Object result =
          jedis.eval(DECR_LUA, 1, key, String.valueOf(group.tokenAmount), String.valueOf(ttl));

      return new QuotaResponse(((Long) result) >= 0);
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Valkey error for quota key: %s", URLEncoder.encode(key, StandardCharsets.UTF_8));
      return new QuotaResponse(true); // Fail open
    }
  }

  /** Refreshes the TTL of an existing quota token. */
  public void refreshQuota(QuotaRequest request) {
    if (jedis == null) {
      return;
    }

    String userId = request.userId();
    QuotaGroup group = customQuotas.getOrDefault(userId, defaultQuota);
    if (group.tokenAmount < 0) {
      return;
    }

    // Use the first ID as the virtual group identity if it's a custom group,
    // otherwise isolate each default user by their actual ID.
    String redisId =
        (group == defaultQuota || group.userId.isEmpty()) ? userId : group.userId.get(0);
    String key = String.format("%s:%s", quotaNamespace, redisId);
    int ttl = group.refillSeconds > 0 ? group.refillSeconds : DEFAULT_TTL_SECONDS;

    try {
      jedis.eval(EXPIRE_LUA, 1, key, String.valueOf(ttl));
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Valkey error refreshing quota for: %s", URLEncoder.encode(key, StandardCharsets.UTF_8));
    }
  }

  /** Returns a token to the pool (used for connection throttling). */
  public void releaseQuota(QuotaRebate rebate) {
    if (jedis == null) {
      return;
    }

    String userId = rebate.userId();
    QuotaGroup group = customQuotas.getOrDefault(userId, defaultQuota);
    if (group.tokenAmount < 0) {
      return;
    }

    // Use the first ID as the virtual group identity if it's a custom group,
    // otherwise isolate each default user by their actual ID.
    String redisId =
        (group == defaultQuota || group.userId.isEmpty()) ? userId : group.userId.get(0);
    String key = String.format("%s:%s", quotaNamespace, redisId);
    try {
      jedis.eval(INCR_LUA, 1, key, String.valueOf(group.tokenAmount));
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Valkey error releasing quota for: %s", URLEncoder.encode(key, StandardCharsets.UTF_8));
    }
  }
}
