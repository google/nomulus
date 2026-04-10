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
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;

/** Dagger module to provide the {@link RedissonClient} for Valkey. */
@Module
public final class CacheModule {

  @Provides
  @Singleton
  public static Optional<RedissonClient> provideRedissonClient(
      @ApplicationDefaultCredential GoogleCredentialsBundle credentialsBundle,
      @Config("valkeyServerAddress") Optional<String> serverAddress,
      @Config("valkeyClusterModeEnabled") boolean clusterModeEnabled) {
    if (serverAddress.isEmpty()) {
      return Optional.empty();
    }
    org.redisson.config.Config config = new org.redisson.config.Config();
    config.setCredentialsResolver(new MemorystoreIamCredentialsResolver(credentialsBundle));
    if (clusterModeEnabled) {
      config.useClusterServers().addNodeAddress(serverAddress.get());
    } else {
      config.useSingleServer().setAddress(serverAddress.get());
    }
    return Optional.of(Redisson.create(config));
  }

  @Provides
  @Singleton
  public static DomainCache provideDomainCache(
      Optional<RedissonClient> redissonClient, Clock clock) {
    if (redissonClient.isPresent()) {
      return new ValkeyDomainCache(redissonClient.get(), clock);
    }
    return domainName ->
        ForeignKeyUtils.loadResourceByCache(Domain.class, domainName, clock.nowUtc());
  }

  @Provides
  @Singleton
  public static HostCache provideHostCache(Optional<RedissonClient> redissonClient) {
    if (redissonClient.isPresent()) {
      return new ValkeyHostCache(redissonClient.get());
    }
    return repoId -> Optional.ofNullable(EppResource.loadByCache(VKey.create(Host.class, repoId)));
  }
}
