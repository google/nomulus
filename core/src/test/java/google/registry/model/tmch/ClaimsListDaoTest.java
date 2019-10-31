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
import static google.registry.testing.JUnitBackports.assertThrows;

import com.google.common.collect.ImmutableMap;
import google.registry.model.transaction.JpaTransactionManagerRule;
import google.registry.schema.tmch.ClaimsList;
import google.registry.testing.FakeClock;
import javax.persistence.NoResultException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ClaimsListDao}. */
@RunWith(JUnit4.class)
public class ClaimsListDaoTest {

  private FakeClock fakeClock = new FakeClock();

  @Rule
  public final JpaTransactionManagerRule jpaTmRule =
      new JpaTransactionManagerRule.Builder().build();

  @Test
  public void trySave_insertsClaimsListSuccessfully() {
    ClaimsList claimsList =
        ClaimsList.create(fakeClock.nowUtc(), ImmutableMap.of("label1", "key1", "label2", "key2"));
    ClaimsListDao.trySave(claimsList);
    ClaimsList insertedClaimsList = ClaimsListDao.getCurrent();
    assertClaimsListEquals(claimsList, insertedClaimsList);
    assertThat(insertedClaimsList.getCreationTimestamp())
        .isEqualTo(jpaTmRule.getTxnClock().nowUtc());
  }

  @Test
  public void trySave_noExceptionThrownWhenSaveFail() {
    ClaimsList claimsList =
        ClaimsList.create(fakeClock.nowUtc(), ImmutableMap.of("label1", "key1", "label2", "key2"));
    ClaimsListDao.trySave(claimsList);
    ClaimsList insertedClaimsList = ClaimsListDao.getCurrent();
    assertClaimsListEquals(claimsList, insertedClaimsList);
    // Save ClaimsList with existing revisionId should fail because revisionId is the primary key.
    ClaimsListDao.trySave(insertedClaimsList);
  }

  @Test
  public void trySave_claimsListWithNoEntries() {
    ClaimsList claimsList = ClaimsList.create(fakeClock.nowUtc(), ImmutableMap.of());
    ClaimsListDao.trySave(claimsList);
    ClaimsList insertedClaimsList = ClaimsListDao.getCurrent();
    assertClaimsListEquals(claimsList, insertedClaimsList);
    assertThat(insertedClaimsList.getLabelsToKeys()).isEmpty();
  }

  @Test
  public void getCurrent_throwsNoResultExceptionIfTableIsEmpty() {
    assertThrows(NoResultException.class, ClaimsListDao::getCurrent);
  }

  @Test
  public void getCurrent_returnsLatestClaims() {
    ClaimsList oldClaimsList =
        ClaimsList.create(fakeClock.nowUtc(), ImmutableMap.of("label1", "key1", "label2", "key2"));
    ClaimsList newClaimsList =
        ClaimsList.create(fakeClock.nowUtc(), ImmutableMap.of("label3", "key3", "label4", "key4"));
    ClaimsListDao.trySave(oldClaimsList);
    ClaimsListDao.trySave(newClaimsList);
    assertClaimsListEquals(newClaimsList, ClaimsListDao.getCurrent());
  }

  private void assertClaimsListEquals(ClaimsList left, ClaimsList right) {
    assertThat(left.getRevisionId()).isEqualTo(right.getRevisionId());
    assertThat(left.getTmdbGenerationTime()).isEqualTo(right.getTmdbGenerationTime());
    assertThat(left.getLabelsToKeys()).isEqualTo(right.getLabelsToKeys());
  }
}
