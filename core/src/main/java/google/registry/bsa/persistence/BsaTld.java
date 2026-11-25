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

package google.registry.bsa.persistence;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.config.RegistryConfig.getSingletonCacheRefreshDuration;
import static google.registry.model.CacheUtils.newCacheBuilder;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableSet;
import google.registry.model.tld.Tld;
import google.registry.persistence.VKey;
import google.registry.util.NonFinalForTesting;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * A {@link Tld} that is ready to be included in BSA processing.
 *
 * <p>A {@code Tld}'s enrollment in BSA is controlled by configuration (See {@link
 * Tld#getBsaEnrollStartTime}). This table records the {@code Tld}s that have been initialized.
 */
@Entity
public final class BsaTld {

  /** A cache that loads the activated BSA TLDs. */
  @VisibleForTesting @NonFinalForTesting
  static LoadingCache<Class<BsaTld>, ImmutableSet<String>> cache =
      createCache(getSingletonCacheRefreshDuration());

  private static LoadingCache<Class<BsaTld>, ImmutableSet<String>> createCache(Duration expiry) {
    return newCacheBuilder(expiry)
        .build(
            ignored ->
                tm().reTransact(
                        () ->
                            tm().loadAllOfStream(BsaTld.class)
                                .map(BsaTld::getTld)
                                .collect(toImmutableSet())));
  }

  @VisibleForTesting
  public static void setCacheForTest(Optional<Duration> expiry) {
    Duration effectiveExpiry = expiry.orElse(getSingletonCacheRefreshDuration());
    cache = createCache(effectiveExpiry);
  }

  /** Returns the names of all TLDs ready for BSA processing. */
  public static ImmutableSet<String> getActivatedBsaTldNames() {
    return cache.get(BsaTld.class);
  }

  @Id String tld;

  /** The time when this tld was properly set up for BSA processing. */
  @Column(nullable = false)
  Instant readyTime;

  @SuppressWarnings("unused") // For Hibernate
  private BsaTld() {}

  public BsaTld(String tld, Instant readyTime) {
    this.tld = tld;
    this.readyTime = readyTime;
  }

  public String getTld() {
    return tld;
  }

  public Instant getReadyTime() {
    return readyTime;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof BsaTld bsaTld)) {
      return false;
    }
    return Objects.equal(tld, bsaTld.tld) && Objects.equal(readyTime, bsaTld.readyTime);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(tld, readyTime);
  }

  public static VKey<BsaTld> createVkey(String tld) {
    return VKey.create(BsaTld.class, tld);
  }
}
