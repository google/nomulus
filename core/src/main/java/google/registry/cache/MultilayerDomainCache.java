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
import jakarta.inject.Inject;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * A multi-layer cache for {@link Domain} objects.
 *
 * <p>It uses a local Caffeine cache, a remote Jedis cache, and finally the database.
 */
public class MultilayerDomainCache extends MultilayerEppResourceCache<Domain>
    implements DomainCache {

  private final Clock clock;

  @Inject
  public MultilayerDomainCache(SimplifiedJedisClient<Domain> jedisClient, Clock clock) {
    super(jedisClient);
    this.clock = clock;
  }

  @Override
  public Optional<Domain> loadByDomainName(String domainName) {
    return load(domainName);
  }

  @Override
  @Nullable
  protected Domain loadFromDatabase(String domainName) {
    // We don't use the cache -- no point in duplicating cache storage
    return ForeignKeyUtils.loadResource(Domain.class, domainName, clock.now()).orElse(null);
  }

  @Override
  protected String getJedisPrefix() {
    return "Domain__";
  }
}
