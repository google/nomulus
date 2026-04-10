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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.persistActiveHost;

import google.registry.model.host.Host;
import google.registry.testing.DatabaseHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.codec.ProtobufCodec;

/** Unit tests for {@link ValkeyHostCache}. */
public class ValkeyHostCacheTest extends ValkeyCacheTest {

  private ValkeyHostCache hostCache;

  @BeforeEach
  void beforeEachDomainCacheTest() {
    hostCache = new ValkeyHostCache(redissonClient);
  }

  @Test
  void testCacheLoad_populatesValkey() {
    Host host = persistActiveHost("ns1.example.tld");
    String repoId = host.getRepoId();
    assertThat(getRemoteCacheMap()).hasSize(0);
    assertThat(hostCache.loadByRepoId(repoId)).hasValue(host);
    // The map in Valkey should be populated now
    RMap<String, Host> remoteCacheMap = getRemoteCacheMap();
    assertThat(remoteCacheMap).hasSize(1);
    assertThat(remoteCacheMap.get(repoId)).isEqualTo(host);
  }

  @Test
  void testCacheLoad_populatesLocalCache() {
    Host host = persistActiveHost("ns1.example.tld");
    String repoId = host.getRepoId();
    assertThat(hostCache.loadByRepoId(repoId)).hasValue(host);

    // Remove the domain from the DB and from Valkey -- it should still be in the local cache
    RMap<String, Host> remoteCacheMap = getRemoteCacheMap();
    remoteCacheMap.remove(repoId);
    DatabaseHelper.deleteResource(host);

    assertThat(hostCache.loadByRepoId(repoId)).hasValue(host);
  }

  private RMap<String, Host> getRemoteCacheMap() {
    return redissonClient.getMap("host-cache", new ProtobufCodec(String.class, Host.class));
  }
}
