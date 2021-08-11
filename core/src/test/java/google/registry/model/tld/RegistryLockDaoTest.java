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

package google.registry.model.tld;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.SqlHelper.getMostRecentRegistryLockByRepoId;
import static google.registry.testing.SqlHelper.getMostRecentUnlockedRegistryLockByRepoId;
import static google.registry.testing.SqlHelper.getMostRecentVerifiedRegistryLockByRepoId;
import static google.registry.testing.SqlHelper.getRegistryLockByRevisionId;
import static google.registry.testing.SqlHelper.getRegistryLockByVerificationCode;
import static google.registry.testing.SqlHelper.getRegistryLocksByRegistrarId;
import static google.registry.testing.SqlHelper.saveRegistryLock;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.EntityTestCase;
import google.registry.schema.domain.RegistryLock;
import java.util.Optional;
import org.joda.time.Duration;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RegistryLockDao}. */
public final class RegistryLockDaoTest extends EntityTestCase {

  RegistryLockDaoTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @Test
  void testSaveAndLoad_success() {
    RegistryLock lock = createLock();
    saveRegistryLock(lock);
    RegistryLock fromDatabase = getRegistryLockByVerificationCode(lock.getVerificationCode()).get();
    assertThat(fromDatabase.getDomainName()).isEqualTo(lock.getDomainName());
    assertThat(fromDatabase.getVerificationCode()).isEqualTo(lock.getVerificationCode());
    assertThat(fromDatabase.getLastUpdateTime()).isEqualTo(fakeClock.nowUtc());
  }

  @Test
  void testSaveTwiceAndLoad_returnsLatest() {
    RegistryLock lock = createLock();
    saveRegistryLock(lock);
    fakeClock.advanceOneMilli();
    jpaTm()
        .transact(
            () -> {
              RegistryLock updatedLock =
                  RegistryLockDao.getByVerificationCode(lock.getVerificationCode()).get();
              RegistryLockDao.save(
                  updatedLock.asBuilder().setLockCompletionTime(fakeClock.nowUtc()).build());
            });
    jpaTm()
        .transact(
            () -> {
              RegistryLock fromDatabase =
                  RegistryLockDao.getByVerificationCode(lock.getVerificationCode()).get();
              assertThat(fromDatabase.getLockCompletionTime().get()).isEqualTo(fakeClock.nowUtc());
              assertThat(fromDatabase.getLastUpdateTime()).isEqualTo(fakeClock.nowUtc());
            });
  }

  @Test
  void testSave_load_withUnlock() {
    RegistryLock lock =
        saveRegistryLock(
            createLock()
                .asBuilder()
                .setLockCompletionTime(fakeClock.nowUtc())
                .setUnlockRequestTime(fakeClock.nowUtc())
                .setUnlockCompletionTime(fakeClock.nowUtc())
                .setRelockDuration(Duration.standardHours(6))
                .build());
    RegistryLock fromDatabase = getRegistryLockByVerificationCode(lock.getVerificationCode()).get();
    assertThat(fromDatabase.getUnlockRequestTime()).isEqualTo(Optional.of(fakeClock.nowUtc()));
    assertThat(fromDatabase.getUnlockCompletionTime()).isEqualTo(Optional.of(fakeClock.nowUtc()));
    assertThat(fromDatabase.isLocked()).isFalse();
    assertThat(fromDatabase.getRelockDuration().get()).isEqualTo(Duration.standardHours(6));
  }

  @Test
  void testUpdateLock_usingSamePrimaryKey() {
    RegistryLock lock = saveRegistryLock(createLock());
    fakeClock.advanceOneMilli();
    RegistryLock updatedLock = lock.asBuilder().setLockCompletionTime(fakeClock.nowUtc()).build();
    saveRegistryLock(updatedLock);
    jpaTm()
        .transact(
            () -> {
              RegistryLock fromDatabase =
                  RegistryLockDao.getByVerificationCode(lock.getVerificationCode()).get();
              assertThat(fromDatabase.getLockCompletionTime())
                  .isEqualTo(Optional.of(fakeClock.nowUtc()));
            });
  }

  @Test
  void testFailure_saveNull() {
    assertThrows(NullPointerException.class, () -> saveRegistryLock(null));
  }

  @Test
  void getLock_unknownCode() {
    assertThat(getRegistryLockByVerificationCode("hi").isPresent()).isFalse();
  }

  @Test
  void testByRevisionId_valid() {
    RegistryLock lock = saveRegistryLock(createLock());
    RegistryLock otherLock = getRegistryLockByRevisionId(lock.getRevisionId()).get();
    // can't do direct comparison due to update time
    assertThat(lock.getDomainName()).isEqualTo(otherLock.getDomainName());
    assertThat(lock.getVerificationCode()).isEqualTo(otherLock.getVerificationCode());
  }

  @Test
  void testByRevisionId_invalid() {
    assertThat(getRegistryLockByRevisionId(8675309L).isPresent()).isFalse();
  }

  @Test
  void testLoad_lockedDomains_byRegistrarId() {
    RegistryLock lock = createLock();
    RegistryLock secondLock =
        createLock()
            .asBuilder()
            .setDomainName("otherexample.test")
            .setLockCompletionTime(fakeClock.nowUtc())
            .build();
    RegistryLock unlockedLock =
        createLock()
            .asBuilder()
            .setDomainName("unlocked.test")
            .setLockCompletionTime(fakeClock.nowUtc())
            .setUnlockRequestTime(fakeClock.nowUtc())
            .setUnlockCompletionTime(fakeClock.nowUtc())
            .build();
    saveRegistryLock(lock);
    saveRegistryLock(secondLock);
    saveRegistryLock(unlockedLock);

    assertThat(
            getRegistryLocksByRegistrarId("TheRegistrar").stream()
                .map(RegistryLock::getDomainName)
                .collect(toImmutableSet()))
        .containsExactly("example.test", "otherexample.test");
    assertThat(getRegistryLocksByRegistrarId("nonexistent")).isEmpty();
  }

  @Test
  void testLoad_byRepoId() {
    RegistryLock completedLock =
        createLock().asBuilder().setLockCompletionTime(fakeClock.nowUtc()).build();
    saveRegistryLock(completedLock);

    fakeClock.advanceOneMilli();
    RegistryLock inProgressLock = createLock();
    saveRegistryLock(inProgressLock);

    Optional<RegistryLock> mostRecent = getMostRecentRegistryLockByRepoId("repoId");
    assertThat(mostRecent.isPresent()).isTrue();
    assertThat(mostRecent.get().isLocked()).isFalse();
  }

  @Test
  void testLoad_byRepoId_empty() {
    assertThat(getMostRecentRegistryLockByRepoId("nonexistent").isPresent()).isFalse();
  }

  @Test
  void testLoad_verified_byRepoId() {
    RegistryLock completedLock =
        createLock().asBuilder().setLockCompletionTime(fakeClock.nowUtc()).build();
    saveRegistryLock(completedLock);

    fakeClock.advanceOneMilli();
    RegistryLock inProgressLock = createLock();
    saveRegistryLock(inProgressLock);

    Optional<RegistryLock> mostRecent = getMostRecentVerifiedRegistryLockByRepoId("repoId");
    assertThat(mostRecent.isPresent()).isTrue();
    assertThat(mostRecent.get().isLocked()).isTrue();
  }

  @Test
  void testLoad_verified_byRepoId_empty() {
    saveRegistryLock(createLock());
    Optional<RegistryLock> mostRecent = getMostRecentVerifiedRegistryLockByRepoId("repoId");
    assertThat(mostRecent.isPresent()).isFalse();
  }

  @Test
  void testLoad_verifiedUnlock_byRepoId() {
    RegistryLock lock =
        saveRegistryLock(
            createLock()
                .asBuilder()
                .setLockCompletionTime(fakeClock.nowUtc())
                .setUnlockRequestTime(fakeClock.nowUtc())
                .setUnlockCompletionTime(fakeClock.nowUtc())
                .build());

    Optional<RegistryLock> mostRecent = getMostRecentUnlockedRegistryLockByRepoId(lock.getRepoId());
    assertThat(mostRecent.get().getRevisionId()).isEqualTo(lock.getRevisionId());
  }

  @Test
  void testLoad_verifiedUnlock_empty() {
    RegistryLock completedLock =
        createLock().asBuilder().setLockCompletionTime(fakeClock.nowUtc()).build();
    saveRegistryLock(completedLock);
    assertThat(getMostRecentUnlockedRegistryLockByRepoId(completedLock.getRepoId()).isPresent())
        .isFalse();
  }

  private RegistryLock createLock() {
    return new RegistryLock.Builder()
        .setRepoId("repoId")
        .setDomainName("example.test")
        .setRegistrarId("TheRegistrar")
        .setVerificationCode("123456789ABCDEFGHJKLMNPQRSTUVWXY")
        .isSuperuser(true)
        .build();
  }
}
