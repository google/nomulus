// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.registry;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.JUnitBackports.assertThrows;

import google.registry.model.transaction.JpaTransactionManagerRule;
import google.registry.schema.domain.RegistryLock;
import google.registry.schema.domain.RegistryLock.Action;
import google.registry.testing.AppEngineRule;
import java.util.UUID;
import javax.persistence.PersistenceException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RegistryLockDao}. */
@RunWith(JUnit4.class)
public final class RegistryLockDaoTest {

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

  @Rule
  public final JpaTransactionManagerRule jpaTmRule =
      new JpaTransactionManagerRule.Builder().build();

  @Test
  public void testSaveAndLoad_success() {
    RegistryLock lock = createLock();
    RegistryLockDao.save(lock);
    RegistryLock fromDatabase = RegistryLockDao.getByVerificationCode(lock.getVerificationCode());
    assertThat(fromDatabase.getDomainName()).isEqualTo(lock.getDomainName());
    assertThat(fromDatabase.getVerificationCode()).isEqualTo(lock.getVerificationCode());
  }

  @Test
  public void testSaveAndLoad_failure_differentCode() {
    RegistryLock lock = createLock();
    RegistryLockDao.save(lock);
    PersistenceException exception =
        assertThrows(
            PersistenceException.class,
            () -> RegistryLockDao.getByVerificationCode(UUID.randomUUID().toString()));
    assertThat(exception)
        .hasCauseThat()
        .hasMessageThat()
        .isEqualTo("No registry lock with this code");
    assertThat(exception).hasCauseThat().isInstanceOf(NullPointerException.class);
  }

  @Test
  public void testSaveTwiceAndLoad_returnsLatest() {
    RegistryLock lock = createLock();
    jpaTm().transact(() -> RegistryLockDao.save(lock));
    jpaTmRule.getTxnClock().advanceOneMilli();
    jpaTm()
        .transact(
            () -> {
              RegistryLock secondLock =
                  RegistryLockDao.getByVerificationCode(lock.getVerificationCode());
              secondLock.setCompletionTimestamp(jpaTmRule.getTxnClock().nowUtc());
              RegistryLockDao.save(secondLock);
            });
    jpaTm()
        .transact(
            () -> {
              RegistryLock fromDatabase =
                  RegistryLockDao.getByVerificationCode(lock.getVerificationCode());
              assertThat(fromDatabase.getCompletionTimestamp().get())
                  .isEqualTo(jpaTmRule.getTxnClock().nowUtc());
            });
  }

  @Test
  public void testFailure_saveNull() {
    assertThrows(NullPointerException.class, () -> RegistryLockDao.save(null));
  }

  @Test
  public void testLoad_byRegistrarId() {
    RegistryLock lock = createLock();
    RegistryLock secondLock = createLock().asBuilder().setDomainName("otherexample.test").build();
    RegistryLockDao.save(lock);
    RegistryLockDao.save(secondLock);

    assertThat(
            RegistryLockDao.getByRegistrarId("TheRegistrar").stream()
                .map(RegistryLock::getDomainName)
                .collect(toImmutableSet()))
        .containsExactly("example.test", "otherexample.test");
    assertThat(RegistryLockDao.getByRegistrarId("nonexistent")).isEmpty();
  }

  private RegistryLock createLock() {
    return new RegistryLock.Builder()
        .setRepoId("repoId")
        .setDomainName("example.test")
        .setRegistrarId("TheRegistrar")
        .setAction(Action.LOCK)
        .setVerificationCode(UUID.randomUUID().toString())
        .isSuperuser(true)
        .build();
  }
}
