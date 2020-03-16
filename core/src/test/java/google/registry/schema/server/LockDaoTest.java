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
import static org.junit.Assert.assertThrows;

import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationWithCoverageRule;
import google.registry.testing.FakeClock;
import java.util.Optional;
import javax.persistence.RollbackException;
import org.joda.time.Duration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link Lock}. */
@RunWith(JUnit4.class)
public class LockDaoTest {

  private final FakeClock fakeClock = new FakeClock();

  @Rule
  public final JpaIntegrationWithCoverageRule jpaRule =
      new JpaTestRules.Builder().withClock(fakeClock).buildIntegrationWithCoverageRule();

  @Test
  public void save_worksSuccessfully() {
    Lock lock =
        Lock.create("testResource", "tld", "testLogId", fakeClock.nowUtc(), Duration.millis(2));
    LockDao.saveNew(lock);
    Optional<Lock> returnedLock = LockDao.load("testResource", "tld");
    assertThat(returnedLock.get().expirationTime).isEqualTo(lock.expirationTime);
    assertThat(returnedLock.get().requestLogId).isEqualTo(lock.requestLogId);
  }

  @Test
  public void save_failsWhenLockAlreadyExists() {
    Lock lock =
        Lock.create("testResource", "tld", "testLogId", fakeClock.nowUtc(), Duration.millis(2));
    LockDao.saveNew(lock);
    Lock lock2 =
        Lock.create("testResource", "tld", "testLogId2", fakeClock.nowUtc(), Duration.millis(4));
    RollbackException thrown = assertThrows(RollbackException.class, () -> LockDao.saveNew(lock2));
    assertThat(thrown.getCause().getCause().getCause().getMessage())
        .contains("duplicate key value violates unique constraint");
  }

  @Test
  public void save_worksSuccesfullyGlobalLock() {
    Lock lock =
        Lock.createGlobal("testResource", "testLogId", fakeClock.nowUtc(), Duration.millis(2));
    LockDao.saveNew(lock);
    Optional<Lock> returnedLock = LockDao.load("testResource");
    assertThat(returnedLock.get().expirationTime).isEqualTo(lock.expirationTime);
    assertThat(returnedLock.get().requestLogId).isEqualTo(lock.requestLogId);
  }

  @Test
  public void load_worksSuccessfully() {
    Lock lock =
        Lock.create("testResource", "tld", "testLogId", fakeClock.nowUtc(), Duration.millis(2));
    LockDao.saveNew(lock);
    Optional<Lock> returnedLock = LockDao.load("testResource", "tld");
    assertThat(returnedLock.get().expirationTime).isEqualTo(lock.expirationTime);
    assertThat(returnedLock.get().requestLogId).isEqualTo(lock.requestLogId);
  }

  @Test
  public void load_worksSuccessfullyGlobalLock() {
    Lock lock =
        Lock.createGlobal("testResource", "testLogId", fakeClock.nowUtc(), Duration.millis(2));
    LockDao.saveNew(lock);
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
    LockDao.saveNew(lock);
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
    LockDao.saveNew(lock);
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
}
