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
import static google.registry.config.RegistryConfig.getSingletonCachePersistDuration;
import static google.registry.config.RegistryConfig.getStaticPremiumListMaxCachedEntries;
import static google.registry.schema.tld.PremiumListDao.getPriceForLabel;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import google.registry.model.registry.label.PremiumList;
import google.registry.util.NonFinalForTesting;
import java.math.BigDecimal;
import java.util.Optional;
import org.joda.time.Duration;

/** Caching utils for {@link PremiumList}s. */
class PremiumListCache {

  /**
   * In-memory cache for premium lists.
   *
   * <p>This is cached for a shorter duration because we need to periodically reload from the DB to
   * check if a new revision has been published, and if so, then use that.
   */
  @NonFinalForTesting
  static LoadingCache<String, Optional<PremiumList>> cachePremiumLists =
      createCachePremiumLists(getDomainLabelListCacheDuration());

  @VisibleForTesting
  static LoadingCache<String, Optional<PremiumList>> createCachePremiumLists(
      Duration cachePersistDuration) {
    return CacheBuilder.newBuilder()
        .expireAfterWrite(java.time.Duration.ofMillis(cachePersistDuration.getMillis()))
        .build(
            new CacheLoader<String, Optional<PremiumList>>() {
              @Override
              public Optional<PremiumList> load(String premiumListName) {
                return PremiumListDao.getLatestRevision(premiumListName);
              }
            });
  }

  /**
   * In-memory price cache for for a given premium list revision and domain label.
   *
   * <p>Note that premium list revision ids are globally unique, so this cache is specific to a
   * given premium list. Premium list entries might not be present, as indicated by the Optional
   * wrapper, and we want to cache that as well.
   *
   * <p>This is cached for a long duration (essentially indefinitely) because premium list revisions
   * are immutable and cannot ever be changed once created, so the cache need not ever expire.
   *
   * <p>A maximum size is set here on the cache because it can potentially grow too big to fit in
   * memory if there are a large number of distinct premium list entries being queried (both those
   * that exist, as well as those that might exist according to the Bloom filter, must be cached).
   * The entries judged least likely to be accessed again will be evicted first.
   */
  @NonFinalForTesting
  static LoadingCache<RevisionIdAndLabel, Optional<BigDecimal>> cachePremiumEntries =
      createCachePremiumEntries(getSingletonCachePersistDuration());

  @VisibleForTesting
  static LoadingCache<RevisionIdAndLabel, Optional<BigDecimal>> createCachePremiumEntries(
      Duration cachePersistDuration) {
    return CacheBuilder.newBuilder()
        .expireAfterWrite(java.time.Duration.ofMillis(cachePersistDuration.getMillis()))
        .maximumSize(getStaticPremiumListMaxCachedEntries())
        .build(
            new CacheLoader<RevisionIdAndLabel, Optional<BigDecimal>>() {
              @Override
              public Optional<BigDecimal> load(RevisionIdAndLabel revisionIdAndLabel) {
                return getPriceForLabel(revisionIdAndLabel);
              }
            });
  }

  @AutoValue
  abstract static class RevisionIdAndLabel {
    abstract long revisionId();

    abstract String label();

    static RevisionIdAndLabel create(long revisionId, String label) {
      return new AutoValue_PremiumListCache_RevisionIdAndLabel(revisionId, label);
    }
  }

  private PremiumListCache() {}
}
