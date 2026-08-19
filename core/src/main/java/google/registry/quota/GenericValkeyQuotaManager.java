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

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.flogger.FluentLogger;
import java.net.URLEncoder;
import java.time.Duration;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import redis.clients.jedis.UnifiedJedis;

/** Generic quota manager that uses Redis/Valkey as the backing store. */
@ThreadSafe
public class GenericValkeyQuotaManager {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * Lua script to atomically decrement a token bucket with a TTL.
   *
   * <p>TODO(b/547996770): maybe use a more complex data structure here (SortedSet?) to manage an
   * actual sliding window. Currently this is a fixed window -- the clock "starts" when the first
   * request arrives and resets back to 0 entirely once the TTL is hit.
   */
  private static final String DECR_LUA =
      """
      local current = redis.call('GET', KEYS[1])
      if not current then
        redis.call('SET', KEYS[1], ARGV[1] - 1, 'PX', ARGV[2])
        return tonumber(ARGV[1]) - 1
      end
      if tonumber(current) <= 0 then
        return -1
      end
      return redis.call('DECR', KEYS[1])
      """;

  /** Lua script to atomically increment back a connection token (capped at max). */
  private static final String INCR_LUA =
      """
      local current = redis.call('GET', KEYS[1])
      if current and tonumber(current) < tonumber(ARGV[1]) then
        return redis.call('INCR', KEYS[1])
      end
      return nil
      """;

  private final UnifiedJedis jedis;
  private final String namespace;

  public GenericValkeyQuotaManager(@Nullable UnifiedJedis jedis, String namespace) {
    this.jedis = jedis;
    this.namespace = namespace;
  }

  /** Attempts to acquire a quota token from Valkey. */
  public boolean acquireQuota(String id, int maxTokenAmount, Duration expirationDuration) {
    if (jedis == null) {
      return true; // Fail open if no Valkey configured
    }
    checkArgument(expirationDuration.isPositive(), "Duration must be positive");
    checkArgument(maxTokenAmount >= 0, "Max token amount must be non-negative");

    String key = createValkeyKey(id);
    try {
      Object result =
          jedis.eval(
              DECR_LUA,
              1,
              key,
              String.valueOf(maxTokenAmount),
              String.valueOf(expirationDuration.toMillis()));
      return (Long) result >= 0;
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Valkey error for quota key: %s", URLEncoder.encode(key, UTF_8));
      // Fail open
      return true;
    }
  }

  /** Refreshes the TTL of an existing quota token. */
  public void refreshQuota(String id, Duration expirationDuration) {
    if (jedis == null) {
      return;
    }
    checkArgument(expirationDuration.isPositive(), "Duration must be positive");

    String key = createValkeyKey(id);
    try {
      jedis.pexpire(key, expirationDuration.toMillis());
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Valkey error refreshing quota for: %s", URLEncoder.encode(key, UTF_8));
    }
  }

  /** Returns a token to the pool (used for connection throttling). */
  public void releaseQuota(String id, int maxTokenAmount) {
    if (jedis == null) {
      return;
    }
    checkArgument(maxTokenAmount >= 0, "Max token amount must be non-negative");

    String key = createValkeyKey(id);
    try {
      jedis.eval(INCR_LUA, 1, key, String.valueOf(maxTokenAmount));
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Valkey error releasing quota for: %s", URLEncoder.encode(key, UTF_8));
    }
  }

  private String createValkeyKey(String id) {
    return String.format("%s:%s", namespace, id);
  }
}
