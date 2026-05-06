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
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableList;
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
    SimplifiedJedisClient client = createJedisClient();
    client.set(new SimplifiedJedisClient.JedisResource<>("example.tld", domain));
    // dsData and gracePeriods get serialized as null instead of the empty set, which is fine
    assertAboutImmutableObjects()
        .that(client.get(Domain.class, "example.tld").get())
        .isEqualExceptFields(domain, "dsData", "gracePeriods");
  }

  @Test
  void testClient_roundTrip_host() {
    Host host = persistActiveHost("ns1.example.tld");
    SimplifiedJedisClient client = createJedisClient();
    client.set(new SimplifiedJedisClient.JedisResource<>("repoId1", host));
    assertThat(client.get(Host.class, "repoId1")).hasValue(host);
  }

  @Test
  void testSet_withExpiration() throws Exception {
    SimplifiedJedisClient client = createJedisClient();
    Domain pendingDelete = persistDeletedDomain("example.tld", DateTime.now(UTC).plusMillis(100));
    client.set(new SimplifiedJedisClient.JedisResource<>("example1.tld", pendingDelete));
    Thread.sleep(101);
    assertThat(client.get(Domain.class, "example1.tld")).isEmpty();
  }

  @Test
  void testPipeline_domain() {
    Domain domain1 = persistActiveDomain("example1.tld");
    Domain domain2 = persistActiveDomain("example2.tld");
    Domain domain3 = persistActiveDomain("example3.tld");
    SimplifiedJedisClient client = createJedisClient();

    client.setAll(
        ImmutableList.of(
            new SimplifiedJedisClient.JedisResource<>("example1.tld", domain1),
            new SimplifiedJedisClient.JedisResource<>("example2.tld", domain2),
            new SimplifiedJedisClient.JedisResource<>("example3.tld", domain3)));

    assertAboutImmutableObjects()
        .that(client.get(Domain.class, "example1.tld").get())
        .isEqualExceptFields(domain1, "dsData", "gracePeriods");
    assertAboutImmutableObjects()
        .that(client.get(Domain.class, "example2.tld").get())
        .isEqualExceptFields(domain2, "dsData", "gracePeriods");
    assertAboutImmutableObjects()
        .that(client.get(Domain.class, "example3.tld").get())
        .isEqualExceptFields(domain3, "dsData", "gracePeriods");
  }

  @Test
  void testPipeline_host() {
    Host host1 = persistActiveHost("ns1.example.tld");
    Host host2 = persistActiveHost("ns2.example.tld");
    Host host3 = persistActiveHost("ns3.example.tld");
    SimplifiedJedisClient client = createJedisClient();

    client.setAll(
        ImmutableList.of(
            new SimplifiedJedisClient.JedisResource<>("repoId1", host1),
            new SimplifiedJedisClient.JedisResource<>("repoId2", host2),
            new SimplifiedJedisClient.JedisResource<>("repoId3", host3)));

    assertThat(client.get(Host.class, "repoId1")).hasValue(host1);
    assertThat(client.get(Host.class, "repoId2")).hasValue(host2);
    assertThat(client.get(Host.class, "repoId3")).hasValue(host3);
  }

  @Test
  void testDelete() {
    Host host1 = persistActiveHost("ns1.example.tld");
    Host host2 = persistActiveHost("ns2.example.tld");
    Host host3 = persistActiveHost("ns3.example.tld");
    SimplifiedJedisClient client = createJedisClient();

    client.setAll(
        ImmutableList.of(
            new SimplifiedJedisClient.JedisResource<>("repoId1", host1),
            new SimplifiedJedisClient.JedisResource<>("repoId2", host2),
            new SimplifiedJedisClient.JedisResource<>("repoId3", host3)));

    client.deleteAll(Host.class, ImmutableList.of("repoId1", "repoId2", "nonexistent"));
    assertThat(client.get(Host.class, "repoId1")).isEmpty();
    assertThat(client.get(Host.class, "repoId2")).isEmpty();
    assertThat(client.get(Host.class, "repoId3")).hasValue(host3);
  }

  @Test
  void testClient_nonexistent() {
    SimplifiedJedisClient domainClient = createJedisClient();
    SimplifiedJedisClient hostClient = createJedisClient();
    assertThat(domainClient.get(Domain.class, "nonexistent.tld")).isEmpty();
    assertThat(hostClient.get(Host.class, "ns1.nonexistent.tld")).isEmpty();
  }

  private SimplifiedJedisClient createJedisClient() {
    return new SimplifiedJedisClient(
        RedisClient.builder()
            .hostAndPort(new HostAndPort(valkey.getHost(), valkey.getFirstMappedPort()))
            .build());
  }
}
