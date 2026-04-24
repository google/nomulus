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

import static google.registry.persistence.transaction.TransactionManagerFactory.replicaTm;

import google.registry.model.host.Host;
import google.registry.persistence.VKey;
import java.util.Optional;

/**
 * A multi-layer cache for {@link Host} objects.
 *
 * <p>It uses a local Caffeine cache, a remote Jedis cache, and finally the database.
 */
public class MultilayerHostCache extends MultilayerEppResourceCache<Host> implements HostCache {

  public MultilayerHostCache(SimplifiedJedisClient<Host> jedisClient) {
    super(jedisClient);
  }

  @Override
  public Optional<Host> loadByRepoId(String repoId) {
    return loadFromCaches(repoId);
  }

  @Override
  protected Optional<Host> loadFromDatabase(String repoId) {
    return replicaTm()
        .transact(() -> replicaTm().loadByKeyIfPresent(VKey.create(Host.class, repoId)));
  }

  @Override
  protected String getJedisPrefix() {
    return "h_";
  }
}
