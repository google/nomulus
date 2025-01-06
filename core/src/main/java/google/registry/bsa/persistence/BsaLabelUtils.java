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

package google.registry.bsa.persistence;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.config.RegistryConfig.getEppResourceCachingDuration;
import static google.registry.config.RegistryConfig.getEppResourceMaxCachedEntries;
import static google.registry.model.CacheUtils.newCacheBuilder;
import static google.registry.persistence.transaction.TransactionManagerFactory.replicaTm;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.persistence.VKey;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Helpers for {@link BsaLabel}. */
public final class BsaLabelUtils {

  private BsaLabelUtils() {}

  static final CacheLoader<VKey<BsaLabel>, Optional<BsaLabel>> CACHE_LOADER =
      new CacheLoader<>() {

        @Override
        public Optional<BsaLabel> load(VKey<BsaLabel> key) {
          return replicaTm().reTransact(() -> replicaTm().loadByKeyIfPresent(key));
        }

        @Override
        public Map<? extends VKey<BsaLabel>, ? extends Optional<BsaLabel>> loadAll(
            Set<? extends VKey<BsaLabel>> keys) {
          ImmutableMap<VKey<? extends BsaLabel>, BsaLabel> existingLabels =
              replicaTm().reTransact(() -> replicaTm().loadByKeysIfPresent(keys));
          return keys.stream()
              .collect(
                  toImmutableMap(key -> key, key -> Optional.ofNullable(existingLabels.get(key))));
        }
      };

  /**
   * A limited size, limited expiry cache of BSA labels.
   *
   * <p>BSA labels are used by the domain creation flow to verify that the requested domain name is
   * not blocked by the BSA program. Label caching is mainly a defense against two scenarios, the
   * initial rush and drop-catching, when clients run back-to-back domain creation requests around
   * the time when a domain becomes available.
   *
   * <p>Because of caching and the use of the replica database, new BSA labels installed in the
   * database will not take effect immediately. A blocked domain may be created due to race
   * condition. A `refresh` job will detect such domains and report them to BSA as unblockable
   * domains.
   *
   * <p>Since the cached BSA labels have the same usage pattern as the cached EppResources, the
   * cache configuration for the latter are reused here.
   */
  private static final LoadingCache<VKey<BsaLabel>, Optional<BsaLabel>> cacheBsaLabels =
      createBsaLabelsCache(getEppResourceCachingDuration());

  private static LoadingCache<VKey<BsaLabel>, Optional<BsaLabel>> createBsaLabelsCache(
      Duration expiry) {
    return newCacheBuilder(expiry)
        .maximumSize(getEppResourceMaxCachedEntries())
        .build(CACHE_LOADER);
  }

  @VisibleForTesting
  void clearCache() {
    cacheBsaLabels.invalidateAll();
  }

  /** Checks if the {@code domainLabel} (the leading `part` of a domain name) is blocked by BSA. */
  public static boolean isLabelBlocked(String domainLabel) {
    return cacheBsaLabels.get(BsaLabel.vKey(domainLabel)).isPresent();
  }

  /** Returns the elements in {@code domainLabels} that are blocked by BSA. */
  public static ImmutableSet<String> getBlockedLabels(ImmutableCollection<String> domainLabels) {
    ImmutableList<VKey<BsaLabel>> queriedLabels =
        domainLabels.stream().map(BsaLabel::vKey).collect(toImmutableList());
    return cacheBsaLabels.getAll(queriedLabels).values().stream()
        .filter(Optional::isPresent)
        .map(Optional::get)
        .map(BsaLabel::getLabel)
        .collect(toImmutableSet());
  }
}
