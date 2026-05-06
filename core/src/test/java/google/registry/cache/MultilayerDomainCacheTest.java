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
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import google.registry.model.domain.Domain;
import google.registry.model.tld.Tld;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link MultilayerDomainCache}. */
public class MultilayerDomainCacheTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private final SimplifiedJedisClient jedisClient = mock(SimplifiedJedisClient.class);
  private final FakeClock clock = new FakeClock();
  private MultilayerDomainCache cache;

  @BeforeEach
  void beforeEach() {
    cache = new MultilayerDomainCache(jedisClient, clock);
    createTld("tld");
  }

  @Test
  void testLoad_fromDatabase_populatesCaches() {
    Domain domain = persistActiveDomain("example.tld");
    assertThat(cache.loadByDomainName("example.tld")).hasValue(domain);

    // We should have filled the caches after one attempt to load from Valkey
    verify(jedisClient).get(Domain.class, "example.tld");
    verify(jedisClient).set(new SimplifiedJedisClient.JedisResource<>("example.tld", domain));

    // Further loads hit the local cache
    assertThat(cache.loadByDomainName("example.tld")).hasValue(domain);
    verifyNoMoreInteractions(jedisClient);
  }

  @Test
  void testLoad_fromValkey() {
    // Note: we don't save the domain to SQL
    Domain domain = DatabaseHelper.newDomain("example.tld");
    // We hit the Valkey cache first
    when(jedisClient.get(Domain.class, "example.tld")).thenReturn(Optional.of(domain));
    assertThat(cache.loadByDomainName("example.tld")).hasValue(domain);
  }

  @Test
  void testSkipsTestTld() {
    persistResource(Tld.get("tld").asBuilder().setTldType(Tld.TldType.TEST).build());

    Domain domain = persistActiveDomain("example.tld");
    assertThat(cache.loadByDomainName("example.tld")).hasValue(domain);

    // This time, we don't populate the remote cache because it's prober data
    verify(jedisClient).get(Domain.class, "example.tld");
    verifyNoMoreInteractions(jedisClient);
  }

  @Test
  void testLoad_missing() {
    assertThat(cache.loadByDomainName("nonexistent.tld")).isEmpty();
  }
}
