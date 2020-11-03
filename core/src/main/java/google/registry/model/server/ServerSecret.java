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

import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Longs;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Unindex;
import google.registry.model.annotations.NotBackedUp;
import google.registry.model.annotations.NotBackedUp.Reason;
import google.registry.model.common.CrossTldSingleton;
import google.registry.persistence.VKey;
import google.registry.schema.replay.DatastoreEntity;
import google.registry.schema.replay.SqlEntity;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import javax.persistence.Column;

/** A secret number used for generating tokens (such as XSRF tokens). */
@Entity
@javax.persistence.Entity
@Unindex
@NotBackedUp(reason = Reason.AUTO_GENERATED)
// TODO(b/27427316): Replace this with an entry in KMSKeyring
public class ServerSecret extends CrossTldSingleton implements DatastoreEntity, SqlEntity {

  /**
   * Cache of the singleton ServerSecret instance that creates it if not present.
   *
   * <p>The key is meaningless since there is only one instance; this is essentially a memoizing
   * Supplier that can be reset for testing purposes.
   */
  private static final LoadingCache<Class<ServerSecret>, ServerSecret> CACHE =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<Class<ServerSecret>, ServerSecret>() {
                @Override
                public ServerSecret load(Class<ServerSecret> unused) {
                  return retrieveAndSaveSecret();
                }
              });

  private static ServerSecret retrieveAndSaveSecret() {
    VKey<ServerSecret> key =
        VKey.create(
            ServerSecret.class,
            SINGLETON_ID,
            Key.create(getCrossTldKey(), ServerSecret.class, SINGLETON_ID));
    return tm().transact(
            () -> {
              ServerSecret secret =
                  tm().maybeLoad(key)
                      .orElseGet(
                          () -> {
                            // transactionally create a new ServerSecret (once per app setup).
                            UUID uuid = UUID.randomUUID();
                            return create(
                                uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
                          });
              // During a dual-write period, write it to both Datastore and SQL
              // even if we didn't have to retrieve it from the DB
              ofyTm().transact(() -> ofyTm().putWithoutBackup(secret));
              jpaTm().transact(() -> jpaTm().putWithoutBackup(secret));
              return secret;
            });
  }

  /** Returns the global ServerSecret instance, creating it if one isn't already in Datastore. */
  public static ServerSecret get() {
    try {
      return CACHE.get(ServerSecret.class);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  /** Most significant 8 bytes of the UUID value. */
  @Column(nullable = false)
  long mostSignificant;

  /** Least significant 8 bytes of the UUID value. */
  @Column(nullable = false)
  long leastSignificant;

  @VisibleForTesting
  static ServerSecret create(long mostSignificant, long leastSignificant) {
    ServerSecret secret = new ServerSecret();
    secret.mostSignificant = mostSignificant;
    secret.leastSignificant = leastSignificant;
    return secret;
  }

  /** Returns the value of this ServerSecret as a UUID. */
  public UUID asUuid() {
    return new UUID(mostSignificant, leastSignificant);
  }

  /** Returns the value of this ServerSecret as a byte array. */
  public byte[] asBytes() {
    return ByteBuffer.allocate(Longs.BYTES * 2)
        .putLong(mostSignificant)
        .putLong(leastSignificant)
        .array();
  }

  @Override
  public ImmutableList<SqlEntity> toSqlEntities() {
    return ImmutableList.of(); // dually-written
  }

  @Override
  public ImmutableList<DatastoreEntity> toDatastoreEntities() {
    return ImmutableList.of(); // dually-written
  }

  @VisibleForTesting
  static void resetCache() {
    CACHE.invalidateAll();
  }
}
