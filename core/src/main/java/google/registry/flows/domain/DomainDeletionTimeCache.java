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

package google.registry.flows.domain;

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.domain.Domain;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/**
 * Functionally-static loading cache that keeps track of deletion (AKA drop) times for domains.
 *
 * <p>Some domain names may have many create requests issued shortly before (and directly after) the
 * name is released due to a previous registrant deleting it. In those cases, caching the deletion
 * time of the existing domain allows us to short-circuit the request and avoid any load on the
 * database checking the existing domain (at least, in cases where the request hits a particular
 * node more than once).
 *
 * <p>The cache is fairly short-lived (as we're concerned about many requests at basically the same
 * time), and entries also expire when the drop actually happens. If the domain is re-created after
 * a drop, the next load attempt will populate the cache with a deletion time of END_OF_TIME, which
 * will be read from the cache by subsequent attempts.
 *
 * <p>We take advantage of the fact that Caffeine caches don't store nulls returned from the
 * CacheLoader, so a null result (meaning the domain doesn't exist) won't affect future calls (this
 * avoids a stale-cache situation where the cache "thinks" the domain doesn't exist, but it does).
 * Put another way, if a domain really doesn't exist, we'll re-attempt the database load every time.
 *
 * <p>We don't explicitly register creates or deletes in the flows themselves in case the
 * transaction fails. It's better to have stale data, or to require an additional database load,
 * than to have incorrect data.
 *
 * <p>Note: this should be injected as a singleton -- it's essentially static, but we have it as a
 * non-static object for concurrent testing purposes.
 */
public class DomainDeletionTimeCache {

  // Max expiry time is ten minutes
  private static final int MAX_EXPIRY_MILLIS = 10 * 60 * 1000;
  private static final int MAX_ENTRIES = 500;
  private static final int NANOS_IN_ONE_MILLISECOND = 100000;

  /**
   * Expire after the max duration, or after the domain is set to drop (whichever comes first).
   *
   * <p>If the domain has already been deleted (the deletion time is <= now), the entry will
   * immediately be expired/removed.
   *
   * <p>NB: the Expiry class requires the return value in <b>nanoseconds</b>, not milliseconds
   */
  private static final Expiry<String, DateTime> EXPIRY_POLICY =
      new Expiry<>() {
        @Override
        public long expireAfterCreate(String key, DateTime value, long currentTime) {
          long millisUntilDeletion = value.getMillis() - tm().getTransactionTime().getMillis();
          return NANOS_IN_ONE_MILLISECOND
              * Math.max(0L, Math.min(MAX_EXPIRY_MILLIS, millisUntilDeletion));
        }

        /** Reset the time entirely on update, as if we were creating the entry anew. */
        @Override
        public long expireAfterUpdate(
            String key, DateTime value, long currentTime, long currentDuration) {
          return expireAfterCreate(key, value, currentTime);
        }

        /** Reads do not change the expiry duration. */
        @Override
        public long expireAfterRead(
            String key, DateTime value, long currentTime, long currentDuration) {
          return currentDuration;
        }
      };

  /** Attempt to load the domain's deletion time if the domain exists. */
  private static final CacheLoader<String, DateTime> CACHE_LOADER =
      (domainName) -> {
        ForeignKeyUtils.MostRecentResource mostRecentResource =
            ForeignKeyUtils.load(Domain.class, ImmutableSet.of(domainName), false).get(domainName);
        if (mostRecentResource == null) {
          return null;
        }
        return mostRecentResource.deletionTime();
      };

  public static DomainDeletionTimeCache create() {
    return new DomainDeletionTimeCache(
        Caffeine.newBuilder()
            .expireAfter(EXPIRY_POLICY)
            .maximumSize(MAX_ENTRIES)
            .build(CACHE_LOADER));
  }

  private final LoadingCache<String, DateTime> cache;

  private DomainDeletionTimeCache(LoadingCache<String, DateTime> cache) {
    this.cache = cache;
  }

  /** Returns the domain's deletion time, or null if it doesn't currently exist. */
  @Nullable
  public DateTime getDeletionTimeForDomain(String domainName) {
    return cache.get(domainName);
  }
}
