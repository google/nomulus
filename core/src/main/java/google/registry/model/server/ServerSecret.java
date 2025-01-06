// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.server;

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.Longs;
import google.registry.model.CacheUtils;
import google.registry.model.common.CrossTldSingleton;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.UUID;

/** A secret number used for generating tokens (such as XSRF tokens). */
@Entity
public class ServerSecret extends CrossTldSingleton {

  /**
   * Cache of the singleton ServerSecret instance that creates it if not present.
   *
   * <p>The key is meaningless since there is only one instance; this is essentially a memoizing
   * Supplier that can be reset for testing purposes.
   */
  private static final LoadingCache<Class<ServerSecret>, ServerSecret> CACHE =
      CacheUtils.newCacheBuilder().build(singletonClazz -> retrieveAndSaveSecret());

  private static ServerSecret retrieveAndSaveSecret() {
    return tm().transact(
            () -> {
              // Make sure we're in a transaction and attempt to load any existing secret, then
              // create it if it's absent.
              Optional<ServerSecret> secret = tm().loadSingleton(ServerSecret.class);
              if (secret.isEmpty()) {
                secret = Optional.of(create(UUID.randomUUID()));
                tm().insert(secret.get());
              }
              return secret.get();
            });
  }

  /** Returns the global ServerSecret instance, creating it if one isn't already in the database. */
  public static ServerSecret get() {
    return CACHE.get(ServerSecret.class);
  }

  /** The UUID value itself. */
  @Column(columnDefinition = "uuid")
  UUID secret;

  @VisibleForTesting
  static ServerSecret create(UUID uuid) {
    ServerSecret secret = new ServerSecret();
    secret.secret = uuid;
    return secret;
  }

  /** Returns the value of this ServerSecret as a byte array. */
  public byte[] asBytes() {
    return ByteBuffer.allocate(Longs.BYTES * 2)
        .putLong(secret.getMostSignificantBits())
        .putLong(secret.getLeastSignificantBits())
        .array();
  }

  @VisibleForTesting
  static void resetCache() {
    CACHE.invalidateAll();
  }
}
