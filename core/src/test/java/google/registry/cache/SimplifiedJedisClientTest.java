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
import static google.registry.testing.DatabaseHelper.persistActiveSubordinateHost;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.util.DateTimeUtils.END_INSTANT;

import com.google.common.collect.ImmutableList;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import io.github.ss_bhatt.testcontainers.valkey.ValkeyContainer;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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

  private final FakeClock fakeClock = new FakeClock(Instant.parse("2025-01-01T00:00:00.000Z"));

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
        .isEqualExceptFields(domain, "dsData", "gracePeriods", "nsHosts");
  }

  @Test
  void testClient_roundTrip_host() {
    Domain domain = persistActiveDomain("example.tld");
    Host host = persistActiveSubordinateHost("ns1.example.tld", domain);
    SimplifiedJedisClient client = createJedisClient();
    client.set(new SimplifiedJedisClient.JedisResource<>("repoId1", host));
    assertThat(client.get(Host.class, "repoId1")).hasValue(host);
  }

  @Test
  void testSet_withExpiration() throws Exception {
    SimplifiedJedisClient client = createJedisClient();
    Domain pendingDelete = persistDeletedDomain("example.tld", fakeClock.now().plusMillis(100));
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
        .isEqualExceptFields(domain1, "dsData", "gracePeriods", "nsHosts");
    assertAboutImmutableObjects()
        .that(client.get(Domain.class, "example2.tld").get())
        .isEqualExceptFields(domain2, "dsData", "gracePeriods", "nsHosts");
    assertAboutImmutableObjects()
        .that(client.get(Domain.class, "example3.tld").get())
        .isEqualExceptFields(domain3, "dsData", "gracePeriods", "nsHosts");
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

  @Test
  void testSet_softDeletedDomain_withExplicitExpiration_retainedInValkey() {
    SimplifiedJedisClient client = createJedisClient();
    Instant deletionTime = fakeClock.now();
    Domain softDeletedDomain = persistDeletedDomain("xap-retained.tld", deletionTime);

    Instant futureExpiration = Instant.parse("2035-01-01T00:00:00.000Z");
    client.set(
        new SimplifiedJedisClient.JedisResource<>(
            "xap-retained.tld", softDeletedDomain, futureExpiration));

    Optional<Domain> cached = client.get(Domain.class, "xap-retained.tld");
    assertThat(cached).isPresent();
    assertAboutImmutableObjects()
        .that(cached.get())
        .isEqualExceptFields(softDeletedDomain, "dsData", "gracePeriods", "nsHosts");
    assertThat(cached.get().getDeletionTime()).isEqualTo(deletionTime);
  }

  @Test
  void testSet_softDeletedDomain_withoutExplicitExpiration_evictedImmediately() {
    SimplifiedJedisClient client = createJedisClient();
    Domain softDeletedDomain = persistDeletedDomain("evicted-immediate.tld", fakeClock.now());

    client.set(
        new SimplifiedJedisClient.JedisResource<>("evicted-immediate.tld", softDeletedDomain));

    assertThat(client.get(Domain.class, "evicted-immediate.tld")).isEmpty();
  }

  @Test
  void testSetAll_mixedResources_pipelineSetsCorrectTtls() {
    SimplifiedJedisClient client = createJedisClient();

    Domain activeDomain = persistActiveDomain("active.tld");
    Domain xapDomain = persistDeletedDomain("xap.tld", fakeClock.now());
    Instant xapExpiration = Instant.parse("2035-01-01T00:00:00.000Z");
    Domain expiredDomain = persistDeletedDomain("expired.tld", fakeClock.now());

    client.setAll(
        ImmutableList.of(
            new SimplifiedJedisClient.JedisResource<>("active.tld", activeDomain),
            new SimplifiedJedisClient.JedisResource<>("xap.tld", xapDomain, xapExpiration),
            new SimplifiedJedisClient.JedisResource<>("expired.tld", expiredDomain)));

    Optional<Domain> cachedActive = client.get(Domain.class, "active.tld");
    assertThat(cachedActive).isPresent();
    assertThat(cachedActive.get().getDeletionTime()).isEqualTo(END_INSTANT);

    Optional<Domain> cachedXap = client.get(Domain.class, "xap.tld");
    assertThat(cachedXap).isPresent();
    assertThat(cachedXap.get().getDeletionTime()).isEqualTo(fakeClock.now());

    assertThat(client.get(Domain.class, "expired.tld")).isEmpty();
  }

  @Test
  void testJedisResource_expirationResolution() {
    Domain domain = persistDeletedDomain("test.tld", fakeClock.now().minus(Duration.ofDays(2)));
    Instant explicitTime = fakeClock.now().plus(Duration.ofDays(5));

    // 2-arg constructor defaults expirationTime to Optional.empty()
    SimplifiedJedisClient.JedisResource<Domain> defaultResource =
        new SimplifiedJedisClient.JedisResource<>("test.tld", domain);
    assertThat(defaultResource.expirationTime()).isEmpty();
    assertThat(defaultResource.getExpirationTime()).isEqualTo(domain.getDeletionTime());

    // 3-arg constructor with explicit Instant
    SimplifiedJedisClient.JedisResource<Domain> explicitResource =
        new SimplifiedJedisClient.JedisResource<>("test.tld", domain, explicitTime);
    assertThat(explicitResource.expirationTime()).hasValue(explicitTime);
    assertThat(explicitResource.getExpirationTime()).isEqualTo(explicitTime);

    // 3-arg constructor with null Instant
    SimplifiedJedisClient.JedisResource<Domain> nullInstantResource =
        new SimplifiedJedisClient.JedisResource<>("test.tld", domain, (Instant) null);
    assertThat(nullInstantResource.expirationTime()).isEmpty();
    assertThat(nullInstantResource.getExpirationTime()).isEqualTo(domain.getDeletionTime());
  }

  @Test
  void testDelete_byClassAndKey_removesKeyFromValkey() {
    SimplifiedJedisClient client = createJedisClient();
    Domain domain1 = persistActiveDomain("to-delete.tld");
    Domain domain2 = persistActiveDomain("to-keep.tld");

    client.setAll(
        ImmutableList.of(
            new SimplifiedJedisClient.JedisResource<>("to-delete.tld", domain1),
            new SimplifiedJedisClient.JedisResource<>("to-keep.tld", domain2)));

    assertThat(client.get(Domain.class, "to-delete.tld")).isPresent();
    assertThat(client.get(Domain.class, "to-keep.tld")).isPresent();

    client.delete(Domain.class, "to-delete.tld");

    assertThat(client.get(Domain.class, "to-delete.tld")).isEmpty();
    assertThat(client.get(Domain.class, "to-keep.tld")).isPresent();
  }

  @Test
  void testDelete_byJedisResource_removesKeyFromValkey() {
    SimplifiedJedisClient client = createJedisClient();
    Domain domain = persistActiveDomain("resource-delete.tld");
    SimplifiedJedisClient.JedisResource<Domain> resource =
        new SimplifiedJedisClient.JedisResource<>("resource-delete.tld", domain);

    client.set(resource);
    assertThat(client.get(Domain.class, "resource-delete.tld")).isPresent();

    client.delete(resource);
    assertThat(client.get(Domain.class, "resource-delete.tld")).isEmpty();
  }

  private SimplifiedJedisClient createJedisClient() {
    return new SimplifiedJedisClient(
        RedisClient.builder()
            .hostAndPort(new HostAndPort(valkey.getHost(), valkey.getFirstMappedPort()))
            .build());
  }
}
