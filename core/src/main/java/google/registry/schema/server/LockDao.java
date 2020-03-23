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

import com.google.common.flogger.FluentLogger;
import google.registry.schema.server.Lock.LockId;
import google.registry.util.DateTimeUtils;
import java.util.Optional;

/** Data access object class for {@link Lock}. */
public class LockDao {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Saves the {@link Lock} object to Cloud SQL. */
  public static void save(Lock lock) {
    jpaTm()
        .transact(
            () -> {
              jpaTm().getEntityManager().merge(lock);
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

  /**
   * Compares a {@link google.registry.model.server.Lock} object with a {@link Lock} object, logging
   * a warning if there are any differences.
   */
  public static void compare(
      Optional<google.registry.model.server.Lock> datastoreLockOptional,
      Optional<Lock> cloudSqlLockOptional) {
    if (!datastoreLockOptional.isPresent()) {
      cloudSqlLockOptional.ifPresent(
          value ->
              logger.atWarning().log(
                  String.format(
                      "Cloud SQL lock for %s with tld %s should be null",
                      value.resourceName, value.tld)));
      return;
    }
    google.registry.schema.server.Lock cloudSqlLock;
    google.registry.model.server.Lock datastoreLock = datastoreLockOptional.get();
    if (cloudSqlLockOptional.isPresent()) {
      cloudSqlLock = cloudSqlLockOptional.get();
      if (!datastoreLock.getRequestLogId().equals(cloudSqlLock.requestLogId)) {
        logger.atWarning().log(
            String.format(
                "Datastore lock requestLogId of %s does not equal Cloud SQL lock requestLogId of"
                    + " %s",
                datastoreLock.getRequestLogId(), cloudSqlLock.requestLogId));
      }
      if (!datastoreLock
          .getAcquiredTime()
          .equals(DateTimeUtils.toJodaDateTime(cloudSqlLock.acquiredTime))) {
        logger.atWarning().log(
            String.format(
                "Datastore lock acquiredTime of %s does not equal Cloud SQL lock acquiredTime of"
                    + " %s",
                datastoreLock.getAcquiredTime(),
                DateTimeUtils.toJodaDateTime(cloudSqlLock.acquiredTime)));
      }
      if (!datastoreLock
          .getExpirationTime()
          .equals(DateTimeUtils.toJodaDateTime(cloudSqlLock.expirationTime))) {
        logger.atWarning().log(
            String.format(
                "Datastore lock expirationTime of %s does not equal Cloud SQL lock expirationTime"
                    + " of %s",
                datastoreLock.getExpirationTime(),
                DateTimeUtils.toJodaDateTime(cloudSqlLock.expirationTime)));
      }
    } else {
      logger.atWarning().log(
          String.format("Datastore lock: %s was not found in Cloud SQL", datastoreLock));
    }
  }
}
