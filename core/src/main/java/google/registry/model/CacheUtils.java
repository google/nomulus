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

package google.registry.model;

import static com.google.common.base.Suppliers.memoizeWithExpiration;
import static google.registry.config.RegistryConfig.getSingletonCacheRefreshDuration;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.Supplier;
import java.time.Duration;

/** Utility methods related to caching database entities. */
public class CacheUtils {

  /**
   * Memoize a supplier, with a short expiration specified in the environment config.
   *
   * <p>Use this for things that might change while code is running. (For example, the various lists
   * downloaded from the TMCH get updated in the database and the caches need to be refreshed.)
   */
  public static <T> Supplier<T> memoizeWithShortExpiration(Supplier<T> original) {
    return tryMemoizeWithExpiration(getSingletonCacheRefreshDuration(), original);
  }

  /**
   * Memoize a supplier with the given expiration. If the expiration is zero(likely happens in a
   * unit test), it returns the original supplier.
   */
  public static <T> Supplier<T> tryMemoizeWithExpiration(
      Duration expiration, Supplier<T> original) {
    return expiration.isZero()
        ? original
        : memoizeWithExpiration(original, expiration.toMillis(), MILLISECONDS);
  }

  /** Creates and returns a new {@link Caffeine} builder. */
  public static Caffeine<Object, Object> newCacheBuilder() {
    return Caffeine.newBuilder();
  }

  /**
   * Creates and returns a new {@link Caffeine} builder with the specified cache expiration.
   *
   * <p>This also sets the refresh duration to half of the cache expiration. The resultant behavior
   * is that a cache entry is eligible to be asynchronously refreshed after access once more than
   * half of its cache duration has elapsed, and then it is synchronously refreshed (blocking the
   * read) once its full cache duration has elapsed. So you will never get data older than the cache
   * expiration, but for frequently accessed keys it will be refreshed more often than that and the
   * cost of the load will never be incurred during the read.
   */
  public static Caffeine<Object, Object> newCacheBuilder(Duration expireAfterWrite) {
    Duration refreshAfterWrite = expireAfterWrite.dividedBy(2);
    Caffeine<Object, Object> caffeine = Caffeine.newBuilder().expireAfterWrite(expireAfterWrite);
    // In tests, the cache duration is usually set to 0, which means the cache load synchronously
    // blocks every time it is called anyway because of the expireAfterWrite() above. Thus, setting
    // the refreshAfterWrite won't do anything, plus it's not legal to call it with a zero value
    // anyway (Caffeine allows expireAfterWrite to be zero but not refreshAfterWrite).
    if (!refreshAfterWrite.isZero()) {
      caffeine = caffeine.refreshAfterWrite(refreshAfterWrite);
    }
    return caffeine;
  }
}
