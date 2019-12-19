// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.schema.tld;

import static google.registry.config.RegistryConfig.getDomainLabelListCacheDuration;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import google.registry.util.NonFinalForTesting;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.joda.time.Duration;

/** Caching utils for {@link ReservedList} */
public class ReservedListCache {

  /**
   * In-memory cache for reserved lists.
   *
   * <p>This is cached for a shorter duration because we need to periodically reload from the DB to
   * check if a new revision has been published, and if so, then use that.
   */
  @NonFinalForTesting
  static LoadingCache<String, Optional<ReservedList>> cacheReservedLists =
      createCacheReservedLists(getDomainLabelListCacheDuration());

  @VisibleForTesting
  static LoadingCache<String, Optional<ReservedList>> createCacheReservedLists(
      Duration cachePersistDuration) {
    return CacheBuilder.newBuilder()
        .expireAfterWrite(cachePersistDuration.getMillis(), MILLISECONDS)
        .build(
            new CacheLoader<String, Optional<ReservedList>>() {
              @Override
              public Optional<ReservedList> load(@NotNull String reservedListName) {
                return ReservedListDao.getLatestRevision(reservedListName);
              }
            });
  }
}
