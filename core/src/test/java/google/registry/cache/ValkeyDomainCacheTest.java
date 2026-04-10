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
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;

import google.registry.model.domain.Domain;
import google.registry.testing.DatabaseHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.codec.ProtobufCodec;

/** Unit tests for {@link ValkeyDomainCache}. */
public class ValkeyDomainCacheTest extends ValkeyCacheTest {

  private ValkeyDomainCache domainCache;

  @BeforeEach
  void beforeEachDomainCacheTest() {
    createTld("tld");
    domainCache = new ValkeyDomainCache(redissonClient, fakeClock);
  }

  @Test
  void testCacheLoad_populatesValkey() {
    Domain domain = persistActiveDomain("example.tld");
    assertThat(getRemoteCacheMap()).hasSize(0);
    assertThat(domainCache.loadByDomainName("example.tld")).hasValue(domain);
    // The map in Valkey should be populated now
    RMap<String, Domain> remoteCacheMap = getRemoteCacheMap();
    assertThat(remoteCacheMap).hasSize(1);
    // The serializer serializes empty sets as null -- ignore them when comparing equality
    assertAboutImmutableObjects()
        .that(remoteCacheMap.get("example.tld"))
        .isEqualExceptFields(domain, "dsData", "gracePeriods");
  }

  @Test
  void testCacheLoad_populatesLocalCache() {
    Domain domain = persistActiveDomain("example.tld");
    assertThat(domainCache.loadByDomainName("example.tld")).hasValue(domain);

    // Remove the domain from the DB and from Valkey -- it should still be in the local cache
    RMap<String, Domain> remoteCacheMap = getRemoteCacheMap();
    remoteCacheMap.remove("example.tld");
    DatabaseHelper.deleteResource(domain);

    assertThat(domainCache.loadByDomainName("example.tld")).hasValue(domain);
  }

  private RMap<String, Domain> getRemoteCacheMap() {
    return redissonClient.getMap("domain-cache", new ProtobufCodec(String.class, Domain.class));
  }
}
