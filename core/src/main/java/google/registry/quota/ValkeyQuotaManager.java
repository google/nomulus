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
import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.flogger.FluentLogger;
import com.google.common.hash.Hashing;
import java.net.URLEncoder;
import java.time.Duration;
import javax.annotation.concurrent.ThreadSafe;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisDataException;

/** Generic quota manager that uses Redis/Valkey as the backing store. */
@ThreadSafe
public class ValkeyQuotaManager implements QuotaManager {

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

  // Valkey has the capability to upload scripts and refer to them by their SHA-1 hash (SHA-1 is OK
  // because the point isn't security or encryption, just shortening). This means we don't need to
  // pass the whole script each time.
  private static final String DECR_LUA_HASH = Hashing.sha1().hashString(DECR_LUA, UTF_8).toString();
  private static final String INCR_LUA_HASH = Hashing.sha1().hashString(INCR_LUA, UTF_8).toString();

  private final UnifiedJedis jedis;
  private final String namespace;

  public ValkeyQuotaManager(UnifiedJedis jedis, String namespace) {
    this.jedis = checkNotNull(jedis, "jedis must not be null");
    this.namespace = checkNotNull(namespace, "namespace must not be null");
  }

  /** Attempts to acquire a quota token from Valkey. */
  @Override
  public boolean acquireQuota(String id, int maxTokenAmount, Duration expirationDuration) {
    checkArgument(expirationDuration.isPositive(), "Duration must be positive");
    checkArgument(maxTokenAmount >= 0, "Max token amount must be non-negative");

    String key = createValkeyKey(id);
    try {
      Object result =
          runScript(
              DECR_LUA,
              DECR_LUA_HASH,
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
  @Override
  public void refreshQuota(String id, Duration expirationDuration) {
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
  @Override
  public void releaseQuota(String id, int maxTokenAmount) {
    checkArgument(maxTokenAmount >= 0, "Max token amount must be non-negative");

    String key = createValkeyKey(id);
    try {
      runScript(INCR_LUA, INCR_LUA_HASH, key, String.valueOf(maxTokenAmount));
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Valkey error releasing quota for: %s", URLEncoder.encode(key, UTF_8));
    }
  }

  private String createValkeyKey(String id) {
    return String.format("%s:%s", namespace, id);
  }

  private Object runScript(String script, String scriptHash, String... params) {
    try {
      return jedis.evalsha(scriptHash, 1, params);
    } catch (JedisDataException e) {
      // Scripts are technically ephemeral and while they aren't evicted during the normal course of
      // operations, we can't guarantee that the Valkey server hasn't restarted
      if (e.getMessage() != null && e.getMessage().startsWith("NOSCRIPT")) {
        jedis.scriptLoad(script);
        return jedis.evalsha(scriptHash, 1, params);
      }
      throw e;
    }
  }
}
