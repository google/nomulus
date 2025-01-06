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

package google.registry.model.tmch;

import static google.registry.config.RegistryConfig.getClaimsListCacheDuration;
import static google.registry.persistence.transaction.QueryComposer.Comparator.EQ;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import google.registry.model.CacheUtils;
import java.time.Duration;
import java.util.Optional;

/** Data access object for {@link ClaimsList}. */
public class ClaimsListDao {

  /**
   * Cache of the {@link ClaimsList} instance.
   *
   * <p>The key is meaningless since we only have one active claims list, this is essentially a
   * memoizing Supplier that can be reset.
   */
  @VisibleForTesting
  static LoadingCache<Class<ClaimsListDao>, ClaimsList> CACHE =
      createCache(getClaimsListCacheDuration());

  @VisibleForTesting
  public static void setCacheForTest(Optional<Duration> expiry) {
    Duration effectiveExpiry = expiry.orElse(getClaimsListCacheDuration());
    CACHE = createCache(effectiveExpiry);
  }

  private static LoadingCache<Class<ClaimsListDao>, ClaimsList> createCache(Duration expiry) {
    return CacheUtils.newCacheBuilder(expiry).build(ignored -> ClaimsListDao.getUncached());
  }

  /** Saves the given {@link ClaimsList} to Cloud SQL. */
  public static void save(ClaimsList claimsList) {
    tm().transact(() -> tm().insert(claimsList));
    CACHE.put(ClaimsListDao.class, claimsList);
  }

  /** Returns the most recent revision of the {@link ClaimsList} from the cache. */
  public static ClaimsList get() {
    return CACHE.get(ClaimsListDao.class);
  }

  /**
   * Returns the most recent revision of the {@link ClaimsList} in SQL or an empty list if it
   * doesn't exist.
   */
  private static ClaimsList getUncached() {
    return tm().reTransact(
            () -> {
              Long revisionId =
                  tm().query("SELECT MAX(revisionId) FROM ClaimsList", Long.class)
                      .getSingleResult();
              return tm().createQueryComposer(ClaimsList.class)
                  .where("revisionId", EQ, revisionId)
                  .first();
            })
        .orElse(ClaimsList.create(START_OF_TIME, ImmutableMap.of()));
  }

  private ClaimsListDao() {}
}
