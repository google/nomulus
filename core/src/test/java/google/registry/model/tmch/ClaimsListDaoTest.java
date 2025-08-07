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

package google.registry.model.tmch;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableMap;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.TestCacheExtension;
import jakarta.persistence.OptimisticLockException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ClaimsListDao}. */
public class ClaimsListDaoTest {

  private final FakeClock fakeClock = new FakeClock();

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder()
          .withClock(fakeClock)
          .withoutCannedData()
          .buildIntegrationWithCoverageExtension();

  // Set long persist times on the cache so it can be tested (cache times default to 0 in tests).
  @RegisterExtension
  public final TestCacheExtension testCacheExtension =
      new TestCacheExtension.Builder().withClaimsListCache(Duration.ofHours(6)).build();

  @Test
  void save_insertsClaimsListSuccessfully() {
    ClaimsList claimsList =
        ClaimsList.create(fakeClock.nowUtc(), ImmutableMap.of("label1", "key1", "label2", "key2"));
    claimsList = ClaimsListDao.save(claimsList);
    ClaimsList insertedClaimsList = ClaimsListDao.get();
    assertClaimsListEquals(claimsList, insertedClaimsList);
    assertThat(insertedClaimsList.getCreationTimestamp()).isEqualTo(fakeClock.nowUtc());
  }

  @Test
  void save_insertsClaimsListSuccessfully_withRetries() {
    ClaimsList claimsList =
        ClaimsList.create(fakeClock.nowUtc(), ImmutableMap.of("label1", "key1", "label2", "key2"));
    AtomicBoolean isFirstAttempt = new AtomicBoolean(true);
    tm().transact(
            () -> {
              ClaimsListDao.save(claimsList);
              if (isFirstAttempt.get()) {
                isFirstAttempt.set(false);
                throw new OptimisticLockException();
              }
            });
    ClaimsList insertedClaimsList = ClaimsListDao.get();
    assertThat(insertedClaimsList.getTmdbGenerationTime())
        .isEqualTo(claimsList.getTmdbGenerationTime());
    assertThat(insertedClaimsList.getLabelsToKeys()).isEqualTo(claimsList.getLabelsToKeys());
    assertThat(insertedClaimsList.getCreationTimestamp()).isEqualTo(fakeClock.nowUtc());
  }

  @Test
  void save_claimsListWithNoEntries() {
    ClaimsList claimsList = ClaimsList.create(fakeClock.nowUtc(), ImmutableMap.of());
    claimsList = ClaimsListDao.save(claimsList);
    ClaimsList insertedClaimsList = ClaimsListDao.get();
    assertClaimsListEquals(claimsList, insertedClaimsList);
    assertThat(insertedClaimsList.getLabelsToKeys()).isEmpty();
  }

  @Test
  void getCurrent_returnsEmptyListIfTableIsEmpty() {
    assertThat(ClaimsListDao.get().labelsToKeys).isEmpty();
  }

  @Test
  void getCurrent_returnsLatestClaims() {
    ClaimsList oldClaimsList =
        ClaimsList.create(fakeClock.nowUtc(), ImmutableMap.of("label1", "key1", "label2", "key2"));
    ClaimsList newClaimsList =
        ClaimsList.create(fakeClock.nowUtc(), ImmutableMap.of("label3", "key3", "label4", "key4"));
    oldClaimsList = ClaimsListDao.save(oldClaimsList);
    newClaimsList = ClaimsListDao.save(newClaimsList);
    assertClaimsListEquals(newClaimsList, ClaimsListDao.get());
  }

  @Test
  void testDaoCaching_savesAndUpdates() {
    assertThat(ClaimsListDao.CACHE.getIfPresent(ClaimsListDao.class)).isNull();
    ClaimsList oldList =
        ClaimsList.create(fakeClock.nowUtc(), ImmutableMap.of("label1", "key1", "label2", "key2"));
    oldList = ClaimsListDao.save(oldList);
    assertThat(ClaimsListDao.CACHE.getIfPresent(ClaimsListDao.class)).isEqualTo(oldList);
    ClaimsList newList =
        ClaimsList.create(fakeClock.nowUtc(), ImmutableMap.of("label3", "key3", "label4", "key4"));
    newList = ClaimsListDao.save(newList);
    assertThat(ClaimsListDao.CACHE.getIfPresent(ClaimsListDao.class)).isEqualTo(newList);
  }

  @Test
  void testEntryCaching_savesAndUpdates() {
    ClaimsList claimsList =
        ClaimsList.create(fakeClock.nowUtc(), ImmutableMap.of("label1", "key1", "label2", "key2"));
    // Bypass the DAO to avoid the cache
    tm().transact(() -> tm().insert(claimsList));
    ClaimsList fromDatabase = ClaimsListDao.get();
    // At first, we haven't loaded any entries
    assertThat(tm().transact(() -> fromDatabase.claimKeyCache.getIfPresent("label1"))).isNull();
    assertThat(tm().transact(() -> fromDatabase.getClaimKey("label1"))).hasValue("key1");
    // After retrieval, the key exists
    assertThat(tm().transact(() -> fromDatabase.claimKeyCache.getIfPresent("label1")))
        .hasValue("key1");
    assertThat(tm().transact(() -> fromDatabase.claimKeyCache.getIfPresent("label2"))).isNull();
    // Loading labels-to-keys should still work
    assertThat(tm().transact(() -> fromDatabase.getLabelsToKeys()))
        .containsExactly("label1", "key1", "label2", "key2");
    // We should also cache nonexistent values
    assertThat(tm().transact(() -> fromDatabase.claimKeyCache.getIfPresent("nonexistent")))
        .isNull();
    assertThat(tm().transact(() -> fromDatabase.getClaimKey("nonexistent"))).isEmpty();
    assertThat(tm().transact(() -> fromDatabase.claimKeyCache.getIfPresent("nonexistent")))
        .isEmpty();
  }

  private void assertClaimsListEquals(ClaimsList left, ClaimsList right) {
    assertThat(left.getRevisionId()).isEqualTo(right.getRevisionId());
    assertThat(left.getTmdbGenerationTime()).isEqualTo(right.getTmdbGenerationTime());
    assertThat(left.getLabelsToKeys()).isEqualTo(right.getLabelsToKeys());
  }
}
