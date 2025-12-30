// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import google.registry.model.CacheUtils;
import google.registry.tmch.RstTmchUtils;
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

  /**
   * Persists a {@link ClaimsList} instance and returns the persisted entity.
   *
   * <p>Note that the input parameter is untouched. Use the returned object if metadata fields like
   * {@code revisionId} are needed.
   */
  public static ClaimsList save(ClaimsList claimsList) {
    var persisted =
        tm().transact(
                () -> {
                  var entity =
                      ClaimsList.create(claimsList.tmdbGenerationTime, claimsList.labelsToKeys);
                  tm().insert(entity);
                  return entity;
                });
    CACHE.put(ClaimsListDao.class, persisted);
    return persisted;
  }

  /** Returns the most recent revision of the {@link ClaimsList} from the cache. */
  public static ClaimsList get() {
    return CACHE.get(ClaimsListDao.class);
  }

  // TODO(b/412715713): remove the tld parameter when RST completes.
  public static ClaimsList get(String tld) {
    return RstTmchUtils.getClaimsList(tld).orElseGet(ClaimsListDao::get);
  }

  /**
   * Returns the most recent revision of the {@link ClaimsList} in SQL or an empty list if it
   * doesn't exist.
   */
  private static ClaimsList getUncached() {
    return tm().reTransact(
            () ->
                tm().query("FROM ClaimsList ORDER BY revisionId DESC", ClaimsList.class)
                    .setMaxResults(1)
                    .getResultStream()
                    .findFirst())
        .orElse(ClaimsList.create(START_OF_TIME, ImmutableMap.of()));
  }

  private ClaimsListDao() {}
}
