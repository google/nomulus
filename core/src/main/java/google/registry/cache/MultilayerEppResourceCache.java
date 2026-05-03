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
import java.time.Duration;
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

  private final SimplifiedJedisClient<V> jedisClient;

  protected MultilayerEppResourceCache(SimplifiedJedisClient<V> jedisClient) {
    this.jedisClient = jedisClient;
  }

  protected abstract Optional<V> loadFromDatabase(String key);

  protected abstract String getJedisPrefix();

  protected boolean shouldPersistToRemoteCache(V value) {
    return true;
  }

  protected Optional<V> loadFromCaches(String key) {
    // hopefully the resource is in the local cache
    Optional<V> possibleValue = Optional.ofNullable(localCache.getIfPresent(key));
    if (possibleValue.isPresent()) {
      return possibleValue;
    }

    // if not, try the remote cache
    String jedisKey = getJedisPrefix() + key;
    possibleValue = jedisClient.get(jedisKey);
    if (possibleValue.isPresent()) {
      localCache.put(key, possibleValue.get());
      return possibleValue;
    }

    // lastly, try the DB
    return loadFromDatabase(key)
        .map(
            v -> {
              // Optional has no direct "peek" functionality to fill the caches
              if (shouldPersistToRemoteCache(v)) {
                jedisClient.set(new SimplifiedJedisClient.JedisResource<>(jedisKey, v));
              }
              localCache.put(key, v);
              return v;
            });
  }
}
