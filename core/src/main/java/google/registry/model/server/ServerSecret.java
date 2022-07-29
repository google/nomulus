// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Ignore;
import com.googlecode.objectify.annotation.OnLoad;
import com.googlecode.objectify.annotation.Unindex;
import google.registry.model.CacheUtils;
import google.registry.model.annotations.NotBackedUp;
import google.registry.model.annotations.NotBackedUp.Reason;
import google.registry.model.common.CrossTldSingleton;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.UUID;
import javax.persistence.Column;
import javax.persistence.PostLoad;
import javax.persistence.Transient;

/** A secret number used for generating tokens (such as XSRF tokens). */
@Entity
@javax.persistence.Entity
@Unindex
@NotBackedUp(reason = Reason.AUTO_GENERATED)
// TODO(b/27427316): Replace this with an entry in KMSKeyring
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
    if (tm().isOfy()) {
      // Attempt a quick load if we're in ofy first to short-circuit sans transaction
      Optional<ServerSecret> secretWithoutTransaction = tm().loadSingleton(ServerSecret.class);
      if (secretWithoutTransaction.isPresent()) {
        return secretWithoutTransaction.get();
      }
    }
    return tm().transact(
            () -> {
              // Make sure we're in a transaction and attempt to load any existing secret, then
              // create it if it's absent.
              Optional<ServerSecret> secret = tm().loadSingleton(ServerSecret.class);
              if (!secret.isPresent()) {
                secret = Optional.of(create(UUID.randomUUID()));
                tm().insert(secret.get());
              }
              return secret.get();
            });
  }

  /** Returns the global ServerSecret instance, creating it if one isn't already in Datastore. */
  public static ServerSecret get() {
    return CACHE.get(ServerSecret.class);
  }

  /** Most significant 8 bytes of the UUID value (stored separately for legacy purposes). */
  @Transient long mostSignificant;

  /** Least significant 8 bytes of the UUID value (stored separately for legacy purposes). */
  @Transient long leastSignificant;

  /** The UUID value itself. */
  @Column(columnDefinition = "uuid")
  @Ignore
  UUID secret;

  /** Convert the Datastore representation to SQL. */
  @OnLoad
  void onLoad() {
    secret = new UUID(mostSignificant, leastSignificant);
  }

  /** Convert the SQL representation to Datastore. */
  @PostLoad
  void postLoad() {
    mostSignificant = secret.getMostSignificantBits();
    leastSignificant = secret.getLeastSignificantBits();
  }

  @VisibleForTesting
  static ServerSecret create(UUID uuid) {
    ServerSecret secret = new ServerSecret();
    secret.mostSignificant = uuid.getMostSignificantBits();
    secret.leastSignificant = uuid.getLeastSignificantBits();
    secret.secret = uuid;
    return secret;
  }

  /** Returns the value of this ServerSecret as a byte array. */
  public byte[] asBytes() {
    return ByteBuffer.allocate(Longs.BYTES * 2)
        .putLong(mostSignificant)
        .putLong(leastSignificant)
        .array();
  }

  @VisibleForTesting
  static void resetCache() {
    CACHE.invalidateAll();
  }
}
