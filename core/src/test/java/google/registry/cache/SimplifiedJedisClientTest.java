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
import static google.registry.testing.DatabaseHelper.persistActiveHost;

import google.registry.model.EppResource;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import io.github.ss_bhatt.testcontainers.valkey.ValkeyContainer;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.UnifiedJedis;

/** Tests for {@link SimplifiedJedisClient}. */
@Testcontainers
public class SimplifiedJedisClientTest {

  @Container private static final ValkeyContainer valkey = new ValkeyContainer();

  private final FakeClock fakeClock = new FakeClock(DateTime.parse("2025-01-01T00:00:00.000Z"));

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    createTld("tld");
  }

  @Test
  void testClient_roundTrip_domain() {
    Domain domain = persistActiveDomain("example.tld");
    SimplifiedJedisClient<Domain> client = createSimplifiedClient(Domain.class);
    client.set("Domain__example.tld", domain);
    // dsData and gracePeriods get serialized as null instead of the empty set, which is fine
    assertAboutImmutableObjects()
        .that(client.get("Domain__example.tld").get())
        .isEqualExceptFields(domain, "dsData", "gracePeriods");
  }

  @Test
  void testClient_roundTrip_host() {
    Host host = persistActiveHost("ns1.example.tld");
    SimplifiedJedisClient<Host> client = createSimplifiedClient(Host.class);
    client.set("Host__ns1.example.tld", host);
    assertThat(client.get("Host__ns1.example.tld")).hasValue(host);
  }

  @Test
  void testClient_nonexistent() {
    SimplifiedJedisClient<Domain> domainClient = createSimplifiedClient(Domain.class);
    SimplifiedJedisClient<Host> hostClient = createSimplifiedClient(Host.class);
    assertThat(domainClient.get("Domain__nonexistent.tld")).isEmpty();
    assertThat(hostClient.get("Host__ns1.nonexistent.tld")).isEmpty();
  }

  private <T extends EppResource> SimplifiedJedisClient<T> createSimplifiedClient(Class<T> clazz) {
    return SimplifiedJedisClient.create(clazz, createJedisClient());
  }

  private UnifiedJedis createJedisClient() {
    return RedisClient.builder()
        .hostAndPort(new HostAndPort(valkey.getHost(), valkey.getFirstMappedPort()))
        .build();
  }
}
