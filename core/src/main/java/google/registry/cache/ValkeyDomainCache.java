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

import google.registry.model.ForeignKeyUtils;
import google.registry.model.domain.Domain;
import google.registry.util.Clock;
import java.util.Optional;
import org.redisson.api.RLocalCachedMap;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.LocalCachedMapOptions;

/** Cache for {@link Domain} objects using Redisson's RLocalCachedMap. */
public class ValkeyDomainCache implements DomainCache {

  private static final String DOMAIN_CACHE_NAME = "domain-cache";

  private final RLocalCachedMap<String, Domain> cache;

  public ValkeyDomainCache(RedissonClient redissonClient, Clock clock) {
    LocalCachedMapOptions<String, Domain> options =
        ValkeyCacheMapOptionsProvider.provideOptions(
            Domain.class,
            DOMAIN_CACHE_NAME,
            (domainName) ->
                ForeignKeyUtils.loadResource(Domain.class, domainName, clock.now()).orElse(null));
    this.cache = redissonClient.getLocalCachedMap(options);
  }

  @Override
  public Optional<Domain> loadByDomainName(String domainName) {
    return Optional.ofNullable(cache.get(domainName));
  }
}
