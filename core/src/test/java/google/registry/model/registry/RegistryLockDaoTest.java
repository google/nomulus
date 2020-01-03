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

import google.registry.model.transaction.JpaTestRules;
import google.registry.model.transaction.JpaTestRules.JpaIntegrationTestRule;
import google.registry.schema.domain.RegistryLock;
import google.registry.testing.AppEngineRule;
import java.util.Optional;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RegistryLockDao}. */
@RunWith(JUnit4.class)
public final class RegistryLockDaoTest {

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

  @Rule
  public final JpaIntegrationTestRule jpaRule =
      new JpaTestRules.Builder().buildIntegrationTestRule();

  @Test
  public void testSaveAndLoad_success() {
    RegistryLock lock = createLock();
    RegistryLockDao.save(lock);
    RegistryLock fromDatabase =
        RegistryLockDao.getByVerificationCode(lock.getVerificationCode()).get();
    assertThat(fromDatabase.getDomainName()).isEqualTo(lock.getDomainName());
    assertThat(fromDatabase.getVerificationCode()).isEqualTo(lock.getVerificationCode());
    assertThat(fromDatabase.getLastUpdateTimestamp()).isEqualTo(jpaRule.getTxnClock().nowUtc());
  }

  @Test
  public void testSaveTwiceAndLoad_returnsLatest() {
    RegistryLock lock = createLock();
    jpaTm().transact(() -> RegistryLockDao.save(lock));
    jpaRule.getTxnClock().advanceOneMilli();
    jpaTm()
        .transact(
            () -> {
              RegistryLock updatedLock =
                  RegistryLockDao.getByVerificationCode(lock.getVerificationCode()).get();
              RegistryLockDao.save(
                  updatedLock
                      .asBuilder()
                      .setLockCompletionTimestamp(jpaRule.getTxnClock().nowUtc())
                      .build());
            });
    jpaTm()
        .transact(
            () -> {
              RegistryLock fromDatabase =
                  RegistryLockDao.getByVerificationCode(lock.getVerificationCode()).get();
              assertThat(fromDatabase.getLockCompletionTimestamp().get())
                  .isEqualTo(jpaRule.getTxnClock().nowUtc());
              assertThat(fromDatabase.getLastUpdateTimestamp())
                  .isEqualTo(jpaRule.getTxnClock().nowUtc());
            });
  }

  @Test
  public void testSave_load_withUnlock() {
    RegistryLock lock =
        RegistryLockDao.save(
            createLock()
                .asBuilder()
                .setLockCompletionTimestamp(jpaRule.getTxnClock().nowUtc())
                .setUnlockRequestTimestamp(jpaRule.getTxnClock().nowUtc())
                .setUnlockCompletionTimestamp(jpaRule.getTxnClock().nowUtc())
                .build());
    RegistryLockDao.save(lock);
    RegistryLock fromDatabase =
        RegistryLockDao.getByVerificationCode(lock.getVerificationCode()).get();
    assertThat(fromDatabase.getUnlockRequestTimestamp())
        .isEqualTo(Optional.of(jpaRule.getTxnClock().nowUtc()));
    assertThat(fromDatabase.getUnlockCompletionTimestamp())
        .isEqualTo(Optional.of(jpaRule.getTxnClock().nowUtc()));
    assertThat(fromDatabase.isLocked()).isFalse();
  }

  @Test
  public void testUpdateLock_usingSamePrimaryKey() {
    RegistryLock lock = RegistryLockDao.save(createLock());
    jpaRule.getTxnClock().advanceOneMilli();
    RegistryLock updatedLock =
        lock.asBuilder().setLockCompletionTimestamp(jpaRule.getTxnClock().nowUtc()).build();
    jpaTm().transact(() -> RegistryLockDao.save(updatedLock));
    jpaTm()
        .transact(
            () -> {
              RegistryLock fromDatabase =
                  RegistryLockDao.getByVerificationCode(lock.getVerificationCode()).get();
              assertThat(fromDatabase.getLockCompletionTimestamp())
                  .isEqualTo(Optional.of(jpaRule.getTxnClock().nowUtc()));
            });
  }

  @Test
  public void testFailure_saveNull() {
    assertThrows(NullPointerException.class, () -> RegistryLockDao.save(null));
  }

  @Test
  public void getLock_unknownCode() {
    assertThat(RegistryLockDao.getByVerificationCode("hi").isPresent()).isFalse();
  }

  @Test
  public void testLoad_lockedDomains_byRegistrarId() {
    RegistryLock lock =
        createLock().asBuilder().setLockCompletionTimestamp(jpaRule.getTxnClock().nowUtc()).build();
    RegistryLock secondLock =
        createLock()
            .asBuilder()
            .setDomainName("otherexample.test")
            .setLockCompletionTimestamp(jpaRule.getTxnClock().nowUtc())
            .build();
    RegistryLock unlockedLock =
        createLock()
            .asBuilder()
            .setDomainName("unlocked.test")
            .setLockCompletionTimestamp(jpaRule.getTxnClock().nowUtc())
            .setUnlockRequestTimestamp(jpaRule.getTxnClock().nowUtc())
            .setUnlockCompletionTimestamp(jpaRule.getTxnClock().nowUtc())
            .build();
    RegistryLockDao.save(lock);
    RegistryLockDao.save(secondLock);
    RegistryLockDao.save(unlockedLock);

    assertThat(
            RegistryLockDao.getLockedDomainsByRegistrarId("TheRegistrar").stream()
                .map(RegistryLock::getDomainName)
                .collect(toImmutableSet()))
        .containsExactly("example.test", "otherexample.test");
    assertThat(RegistryLockDao.getLockedDomainsByRegistrarId("nonexistent")).isEmpty();
  }

  @Test
  public void testLoad_byRepoId() {
    RegistryLock completedLock =
        createLock().asBuilder().setLockCompletionTimestamp(jpaRule.getTxnClock().nowUtc()).build();
    RegistryLockDao.save(completedLock);

    jpaRule.getTxnClock().advanceOneMilli();
    RegistryLock inProgressLock = createLock();
    RegistryLockDao.save(inProgressLock);

    Optional<RegistryLock> mostRecent = RegistryLockDao.getMostRecentByRepoId("repoId");
    assertThat(mostRecent.isPresent()).isTrue();
    assertThat(mostRecent.get().isLocked()).isFalse();
  }

  @Test
  public void testLoad_byRepoId_empty() {
    assertThat(RegistryLockDao.getMostRecentByRepoId("nonexistent").isPresent()).isFalse();
  }

  @Test
  public void testLoad_verified_byRepoId() {
    RegistryLock completedLock =
        createLock().asBuilder().setLockCompletionTimestamp(jpaRule.getTxnClock().nowUtc()).build();
    RegistryLockDao.save(completedLock);

    jpaRule.getTxnClock().advanceOneMilli();
    RegistryLock inProgressLock = createLock();
    RegistryLockDao.save(inProgressLock);

    Optional<RegistryLock> mostRecent = RegistryLockDao.getMostRecentVerifiedLockByRepoId("repoId");
    assertThat(mostRecent.isPresent()).isTrue();
    assertThat(mostRecent.get().isLocked()).isTrue();
  }

  @Test
  public void testLoad_verified_byRepoId_empty() {
    RegistryLockDao.save(createLock());
    Optional<RegistryLock> mostRecent = RegistryLockDao.getMostRecentVerifiedLockByRepoId("repoId");
    assertThat(mostRecent.isPresent()).isFalse();
  }

  private RegistryLock createLock() {
    return new RegistryLock.Builder()
        .setRepoId("repoId")
        .setDomainName("example.test")
        .setRegistrarId("TheRegistrar")
        .setVerificationCode(UUID.randomUUID().toString())
        .isSuperuser(true)
        .build();
  }
}
