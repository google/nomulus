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
import java.time.Duration;
import java.util.function.Function;
import org.redisson.api.map.MapLoader;
import org.redisson.api.options.LocalCachedMapOptions;
import org.redisson.api.options.LocalCachedMapOptions.CacheProvider;
import org.redisson.codec.ProtobufCodec;

/** Common-options provider for creating a local+Valkey cache of an {@link EppResource}. */
public class ValkeyCacheMapOptionsProvider {

  static <T extends EppResource> LocalCachedMapOptions<String, T> provideOptions(
      Class<T> clazz, String name, Function<String, T> loader) {
    return LocalCachedMapOptions.<String, T>name(name)
        .cacheSize(1000)
        .cacheProvider(CacheProvider.CAFFEINE)
        // The default Kryo5 codec doesn't play nice with immutable collections
        .codec(new ProtobufCodec(String.class, clazz))
        // nb: this is the TTL of the local cache, not Valkey
        .timeToLive(Duration.ofHours(1))
        .syncStrategy(LocalCachedMapOptions.SyncStrategy.INVALIDATE)
        .loader(
            new MapLoader<>() {
              @Override
              public T load(String key) {
                // We are purposefully not in a transaction before this call -- hopefully we can
                // load the resource without one.
                return loader.apply(key);
              }

              @Override
              public Iterable<String> loadAllKeys() {
                // We really shouldn't try to load all keys
                return null;
              }
            });
  }

  private ValkeyCacheMapOptionsProvider() {}
}
