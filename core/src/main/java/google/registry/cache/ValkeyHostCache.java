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

import google.registry.model.EppResource;
import google.registry.model.host.Host;
import google.registry.persistence.VKey;
import java.util.Optional;
import org.redisson.api.RLocalCachedMap;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.LocalCachedMapOptions;

/** Cache for {@link Host} objects using Redisson's RLocalCachedMap. */
public class ValkeyHostCache implements HostCache {

  private static final String HOST_CACHE_NAME = "host-cache";

  private final RLocalCachedMap<String, Host> cache;

  public ValkeyHostCache(RedissonClient redissonClient) {
    LocalCachedMapOptions<String, Host> options =
        ValkeyCacheMapOptionsProvider.provideOptions(
            Host.class,
            HOST_CACHE_NAME,
            repoId -> EppResource.loadByCache(VKey.create(Host.class, repoId)));
    this.cache = redissonClient.getLocalCachedMap(options);
  }

  @Override
  public Optional<Host> loadByRepoId(String repoId) {
    return Optional.ofNullable(cache.get(repoId));
  }
}
