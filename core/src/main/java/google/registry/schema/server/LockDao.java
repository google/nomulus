// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.schema.server;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.schema.server.Lock.GLOBAL;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import google.registry.schema.server.Lock.LockId;
import java.util.Optional;

/** Data access object class for {@link Lock}. */
public class LockDao {

  /** Saves the {@link Lock} object to Cloud SQL. */
  public static void saveNew(Lock lock) {
    jpaTm()
        .transact(
            () -> {
              jpaTm().getEntityManager().persist(lock);
            });
  }

  /**
   * Loads and returns a {@link Lock} object with the given resourceName and tld from Cloud SQL if
   * it exists, else empty.
   */
  public static Optional<Lock> load(String resourceName, String tld) {
    checkArgumentNotNull(resourceName, "The resource name of the lock to load cannot be null");
    checkArgumentNotNull(tld, "The tld of the lock to load cannot be null");
    return Optional.ofNullable(
        jpaTm()
            .transact(
                () -> jpaTm().getEntityManager().find(Lock.class, new LockId(resourceName, tld))));
  }

  /**
   * Loads a global {@link Lock} object with the given resourceName from Cloud SQL if it exists,
   * else empty.
   */
  public static Optional<Lock> load(String resourceName) {
    return load(resourceName, GLOBAL);
  }

  /**
   * Deletes the {@link Lock} object with the given resourceName and tld from Cloud SQL. This method
   * is idempotent and will simply return if the lock has already been deleted.
   */
  public static void delete(String resourceName, String tld) {
    jpaTm()
        .transact(
            () -> {
              Optional<Lock> loadedLock = load(resourceName, tld);
              if (loadedLock.isPresent()) {
                jpaTm().getEntityManager().remove(loadedLock.get());
              }
            });
  }

  /**
   * Deletes the global {@link Lock} object with the given resourceName from Cloud SQL. This method
   * is idempotent and will simply return if the lock has already been deleted.
   */
  public static void delete(String resourceName) {
    delete(resourceName, GLOBAL);
  }
}
