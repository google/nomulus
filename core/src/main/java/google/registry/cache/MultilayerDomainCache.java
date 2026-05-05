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

import com.google.common.collect.ImmutableList;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.domain.Domain;
import google.registry.model.tld.Tld;
import google.registry.util.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * A multi-layer cache for {@link Domain} objects.
 *
 * <p>It uses a local Caffeine cache, a remote Jedis cache, and finally the database.
 */
public class MultilayerDomainCache extends MultilayerEppResourceCache<Domain>
    implements DomainCache {

  private final Clock clock;

  public MultilayerDomainCache(
      SimplifiedJedisClient jedisClient, Clock clock, CacheMetrics cacheMetrics) {
    super(jedisClient, cacheMetrics);
    this.clock = clock;
  }

  @Override
  public Optional<Domain> loadByDomainName(String domainName) {
    return loadFromCaches(Domain.class, domainName);
  }

  @Override
  protected Optional<Domain> loadFromDatabase(String domainName) {
    // Don't use the cache (avoid caching the same domain twice). Do use the replica SQL instance.
    Optional<Domain> possibleDomain =
        Optional.ofNullable(
            ForeignKeyUtils.loadMostRecentResourceObjects(
                    Domain.class, ImmutableList.of(domainName), true)
                .get(domainName));
    Instant now = clock.now();
    return possibleDomain
        .filter(domain -> now.isBefore(domain.getDeletionTime()))
        .map(domain -> domain.cloneProjectedAtTime(now));
  }

  @Override
  protected boolean shouldPersistToRemoteCache(Domain domain) {
    return Tld.get(domain.getTld()).getTldType().equals(Tld.TldType.REAL);
  }
}
