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

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule.ApplicationDefaultCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.EppResource;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.persistence.VKey;
import google.registry.util.Clock;
import google.registry.util.GoogleCredentialsBundle;
import jakarta.inject.Singleton;
import java.util.Optional;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.RedisClusterClient;
import redis.clients.jedis.UnifiedJedis;

/** Dagger module to provide the {@link Jedis}-based cache for Valkey. */
@Module
public final class CacheModule {

  @Provides
  @Singleton
  public static Optional<UnifiedJedis> provideJedis(
      @ApplicationDefaultCredential GoogleCredentialsBundle credentialsBundle,
      @Config("valkeyHostsAndPorts") Optional<ImmutableList<String>> valkeyHostsAndPorts) {
    if (valkeyHostsAndPorts.map(ImmutableList::isEmpty).orElse(true)) {
      return Optional.empty();
    }
    ImmutableSet<HostAndPort> hostsAndPorts =
        valkeyHostsAndPorts.get().stream().map(HostAndPort::from).collect(toImmutableSet());
    JedisClientConfig clientConfig =
        DefaultJedisClientConfig.builder()
            .credentialsProvider(new ValkeyCredentialsProvider(credentialsBundle))
            .build();
    if (hostsAndPorts.size() > 1) {
      return Optional.of(
          RedisClusterClient.builder().clientConfig(clientConfig).nodes(hostsAndPorts).build());
    }
    return Optional.of(
        RedisClient.builder()
            .clientConfig(clientConfig)
            .hostAndPort(Iterables.getOnlyElement(hostsAndPorts))
            .build());
  }

  @Provides
  @Singleton
  public static DomainCache provideDomainCache(Optional<UnifiedJedis> jedis, Clock clock) {
    if (jedis.isEmpty()) {
      return domainName ->
          ForeignKeyUtils.loadResourceByCache(Domain.class, domainName, clock.nowUtc());
    }
    SimplifiedJedisClient<Domain> jedisClient =
        SimplifiedJedisClient.create(Domain.class, jedis.get());
    return new MultilayerDomainCache(jedisClient, clock);
  }

  @Provides
  @Singleton
  public static HostCache provideHostCache(Optional<UnifiedJedis> jedis) {
    if (jedis.isEmpty()) {
      return repoId ->
          Optional.ofNullable(EppResource.loadByCache(VKey.create(Host.class, repoId)));
    }
    SimplifiedJedisClient<Host> jedisClient = SimplifiedJedisClient.create(Host.class, jedis.get());
    return new MultilayerHostCache(jedisClient);
  }
}
