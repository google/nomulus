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

package google.registry.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import google.registry.config.RegistryConfig;
import google.registry.model.EppResource;
import google.registry.util.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * A multi-layer cache for {@link EppResource}s.
 *
 * <p>It uses a local Caffeine cache, a remote Jedis cache, and finally the database.
 */
public abstract class MultilayerEppResourceCache<V extends EppResource> {

  // Don't use a loading cache; it'd complicate the nesting
  private final Cache<String, V> localCache =
      Caffeine.newBuilder()
          .expireAfterWrite(Duration.ofHours(1))
          .maximumSize(RegistryConfig.getEppResourceMaxCachedEntries())
          .build();

  private final SimplifiedJedisClient jedisClient;
  private final Clock clock;
  private final CacheMetrics cacheMetrics;

  protected MultilayerEppResourceCache(
      SimplifiedJedisClient jedisClient, Clock clock, CacheMetrics cacheMetrics) {
    this.jedisClient = jedisClient;
    this.clock = clock;
    this.cacheMetrics = cacheMetrics;
  }

  protected abstract Optional<V> loadFromDatabase(String key);

  protected boolean shouldPersistToRemoteCache(V value, Instant now) {
    return getExpirationTime(value).isAfter(now);
  }

  protected Instant getExpirationTime(V resource) {
    return resource.getDeletionTime();
  }

  protected Optional<V> loadFromCaches(Class<V> clazz, String key) {
    Instant now = clock.now();
    return loadFromCachesIncludingDeleted(clazz, key, now)
        .filter(v -> now.isBefore(v.getDeletionTime()));
  }

  protected Optional<V> loadFromCachesIncludingDeleted(Class<V> clazz, String key) {
    return loadFromCachesIncludingDeleted(clazz, key, clock.now());
  }

  @SuppressWarnings("unchecked")
  private Optional<V> loadFromCachesIncludingDeleted(Class<V> clazz, String key, Instant now) {
    return (Optional<V>)
        loadFromCachesInternal(clazz, key, now).map(v -> v.cloneProjectedAtTime(now));
  }

  private Optional<V> loadFromCachesInternal(Class<V> clazz, String key, Instant now) {
    // hopefully the resource is in the local cache
    Optional<V> possibleValue = Optional.ofNullable(localCache.getIfPresent(key));
    if (possibleValue.isPresent()) {
      cacheMetrics.recordLookup(clazz.getSimpleName(), CacheMetrics.CacheHitType.LOCAL);
      return possibleValue;
    }

    // if not, try the remote cache
    possibleValue = jedisClient.get(clazz, key);
    if (possibleValue.isPresent()) {
      localCache.put(key, possibleValue.get());
      cacheMetrics.recordLookup(clazz.getSimpleName(), CacheMetrics.CacheHitType.REMOTE);
      return possibleValue;
    }

    // lastly, try the DB
    possibleValue = loadFromDatabase(key);
    if (possibleValue.isEmpty()) {
      cacheMetrics.recordLookup(clazz.getSimpleName(), CacheMetrics.CacheHitType.MISS_NONEXISTENT);
      return possibleValue;
    }
    V value = possibleValue.get();
    if (shouldPersistToRemoteCache(value, now)) {
      jedisClient.set(
          new SimplifiedJedisClient.JedisResource<>(key, value, getExpirationTime(value)));
    }
    localCache.put(key, value);
    cacheMetrics.recordLookup(clazz.getSimpleName(), CacheMetrics.CacheHitType.MISS);
    return possibleValue;
  }
}
