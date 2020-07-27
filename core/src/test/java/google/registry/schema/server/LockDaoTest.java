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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.LogsSubject.assertAboutLogs;

import com.google.common.testing.TestLogHandler;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationWithCoverageExtension;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.joda.time.Duration;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link Lock}. */
public class LockDaoTest {

  private final FakeClock fakeClock = new FakeClock();
  private final TestLogHandler logHandler = new TestLogHandler();
  private final Logger loggerToIntercept = Logger.getLogger(LockDao.class.getCanonicalName());

  @RegisterExtension
  @Order(value = 1)
  public DatastoreEntityExtension datastoreEntityExtension = new DatastoreEntityExtension();

  @RegisterExtension
  public final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestRules.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  @Test
  public void save_worksSuccessfully() {
    Lock lock =
        Lock.create("testResource", "tld", "testLogId", fakeClock.nowUtc(), Duration.millis(2));
    LockDao.save(lock);
    Optional<Lock> returnedLock = LockDao.load("testResource", "tld");
    assertThat(returnedLock.get().expirationTime).isEqualTo(lock.expirationTime);
    assertThat(returnedLock.get().requestLogId).isEqualTo(lock.requestLogId);
  }

  @Test
  public void save_succeedsWhenLockAlreadyExists() {
    Lock lock =
        Lock.create("testResource", "tld", "testLogId", fakeClock.nowUtc(), Duration.millis(2));
    LockDao.save(lock);
    Lock lock2 =
        Lock.create("testResource", "tld", "testLogId2", fakeClock.nowUtc(), Duration.millis(4));
    LockDao.save(lock2);
    assertThat(LockDao.load("testResource", "tld").get().requestLogId).isEqualTo("testLogId2");
  }

  @Test
  public void save_worksSuccesfullyGlobalLock() {
    Lock lock =
        Lock.createGlobal("testResource", "testLogId", fakeClock.nowUtc(), Duration.millis(2));
    LockDao.save(lock);
    Optional<Lock> returnedLock = LockDao.load("testResource");
    assertThat(returnedLock.get().expirationTime).isEqualTo(lock.expirationTime);
    assertThat(returnedLock.get().requestLogId).isEqualTo(lock.requestLogId);
  }

  @Test
  public void load_worksSuccessfully() {
    Lock lock =
        Lock.create("testResource", "tld", "testLogId", fakeClock.nowUtc(), Duration.millis(2));
    LockDao.save(lock);
    Optional<Lock> returnedLock = LockDao.load("testResource", "tld");
    assertThat(returnedLock.get().expirationTime).isEqualTo(lock.expirationTime);
    assertThat(returnedLock.get().requestLogId).isEqualTo(lock.requestLogId);
  }

  @Test
  public void load_worksSuccessfullyGlobalLock() {
    Lock lock =
        Lock.createGlobal("testResource", "testLogId", fakeClock.nowUtc(), Duration.millis(2));
    LockDao.save(lock);
    Optional<Lock> returnedLock = LockDao.load("testResource");
    assertThat(returnedLock.get().expirationTime).isEqualTo(lock.expirationTime);
    assertThat(returnedLock.get().requestLogId).isEqualTo(lock.requestLogId);
  }

  @Test
  public void load_worksSuccesfullyLockDoesNotExist() {
    Optional<Lock> returnedLock = LockDao.load("testResource", "tld");
    assertThat(returnedLock.isPresent()).isFalse();
  }

  @Test
  public void delete_worksSuccesfully() {
    Lock lock =
        Lock.create("testResource", "tld", "testLogId", fakeClock.nowUtc(), Duration.millis(2));
    LockDao.save(lock);
    Optional<Lock> returnedLock = LockDao.load("testResource", "tld");
    assertThat(returnedLock.get().expirationTime).isEqualTo(lock.expirationTime);
    LockDao.delete("testResource", "tld");
    returnedLock = LockDao.load("testResource", "tld");
    assertThat(returnedLock.isPresent()).isFalse();
  }

  @Test
  public void delete_worksSuccessfullyGlobalLock() {
    Lock lock =
        Lock.createGlobal("testResource", "testLogId", fakeClock.nowUtc(), Duration.millis(2));
    LockDao.save(lock);
    Optional<Lock> returnedLock = LockDao.load("testResource");
    assertThat(returnedLock.get().expirationTime).isEqualTo(lock.expirationTime);
    LockDao.delete("testResource");
    returnedLock = LockDao.load("testResource");
    assertThat(returnedLock.isPresent()).isFalse();
  }

  @Test
  public void delete_succeedsLockDoesntExist() {
    LockDao.delete("testResource");
  }

  @Test
  public void compare_logsWarningWhenCloudSqlLockMissing() {
    loggerToIntercept.addHandler(logHandler);
    google.registry.model.server.Lock datastoreLock =
        google.registry.model.server.Lock.create(
            "resourceName", "tld", "id", fakeClock.nowUtc(), Duration.millis(2));
    LockDao.compare(Optional.of(datastoreLock), Optional.empty());
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.WARNING,
            String.format("Datastore lock: %s was not found in Cloud SQL", datastoreLock));
  }

  @Test
  public void compare_logsWarningWhenCloudSqlLockExistsWhenItShouldNot() {
    loggerToIntercept.addHandler(logHandler);
    Lock lock =
        Lock.createGlobal("testResource", "testLogId", fakeClock.nowUtc(), Duration.millis(2));
    LockDao.compare(Optional.ofNullable(null), Optional.of(lock));
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.WARNING,
            String.format("Cloud SQL lock for testResource with tld GLOBAL should be null"));
  }

  @Test
  public void compare_logsWarningWhenLocksDontMatch() {
    loggerToIntercept.addHandler(logHandler);
    Lock cloudSqlLock =
        Lock.create("testResource", "tld", "testLogId", fakeClock.nowUtc(), Duration.millis(2));
    google.registry.model.server.Lock datastoreLock =
        google.registry.model.server.Lock.create(
            "testResource", "tld", "wrong", fakeClock.nowUtc().minusDays(1), Duration.millis(3));
    LockDao.compare(Optional.of(datastoreLock), Optional.of(cloudSqlLock));
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.WARNING,
            String.format(
                "Datastore lock requestLogId of wrong does not equal Cloud SQL lock requestLogId"
                    + " of testLogId"));
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.WARNING,
            String.format(
                "Datastore lock acquiredTime of 1969-12-31T00:00:00.000Z does not equal Cloud SQL"
                    + " lock acquiredTime of 1970-01-01T00:00:00.000Z"));
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.WARNING,
            String.format(
                "Datastore lock expirationTime of 1969-12-31T00:00:00.003Z does not equal Cloud"
                    + " SQL lock expirationTime of 1970-01-01T00:00:00.002Z"));
  }
}
