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

package google.registry.schema.tld;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableMap;
import google.registry.model.registry.label.ReservationType;
import google.registry.model.transaction.JpaTestRules;
import google.registry.model.transaction.JpaTestRules.JpaIntegrationTestRule;
import google.registry.schema.tld.ReservedList.ReservedEntry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ReservedListDao}. */
@RunWith(JUnit4.class)
public class ReservedListDaoTest {
  @Rule
  public final JpaIntegrationTestRule jpaRule =
      new JpaTestRules.Builder().buildIntegrationTestRule();

  private static final ImmutableMap<String, ReservedEntry> TEST_RESERVATIONS =
      ImmutableMap.of(
          "food",
          ReservedEntry.create(ReservationType.RESERVED_FOR_SPECIFIC_USE, null),
          "music",
          ReservedEntry.create(ReservationType.FULLY_BLOCKED, "fully blocked"));

  @Test
  public void save_worksSuccessfully() {
    ReservedList reservedList = ReservedList.create("testname", false, TEST_RESERVATIONS);
    ReservedListDao.save(reservedList);
    jpaTm()
        .transact(
            () -> {
              ReservedList persistedList =
                  jpaTm()
                      .getEntityManager()
                      .createQuery("FROM ReservedList WHERE name = :name", ReservedList.class)
                      .setParameter("name", "testname")
                      .getSingleResult();
              assertThat(persistedList.getLabelsToReservations())
                  .containsExactlyEntriesIn(TEST_RESERVATIONS);
              assertThat(persistedList.getCreationTimestamp())
                  .isEqualTo(jpaRule.getTxnClock().nowUtc());
            });
  }

  @Test
  public void checkExists_worksSuccessfully() {
    assertThat(ReservedListDao.checkExists("testlist")).isFalse();
    ReservedListDao.save(ReservedList.create("testlist", false, TEST_RESERVATIONS));
    assertThat(ReservedListDao.checkExists("testlist")).isTrue();
  }

  @Test
  public void getLatestRevision_worksSuccessfully() {
    assertThat(ReservedListDao.getLatestRevision("testlist").isPresent()).isFalse();
    ReservedListDao.save(ReservedList.create("testlist", false, TEST_RESERVATIONS));
    ReservedList persistedList = ReservedListDao.getLatestRevision("testlist").get();
    assertThat(persistedList.getRevisionId()).isNotNull();
    assertThat(persistedList.getCreationTimestamp()).isEqualTo(jpaRule.getTxnClock().nowUtc());
    assertThat(persistedList.getName()).isEqualTo("testlist");
    assertThat(persistedList.getShouldPublish()).isFalse();
    assertThat(persistedList.getLabelsToReservations()).containsExactlyEntriesIn(TEST_RESERVATIONS);
  }

  @Test
  public void getLatestRevision_returnsLatestRevision() {
    ReservedListDao.save(
        ReservedList.create(
            "testlist",
            false,
            ImmutableMap.of(
                "old", ReservedEntry.create(ReservationType.RESERVED_FOR_SPECIFIC_USE, null))));
    ReservedListDao.save(ReservedList.create("testlist", false, TEST_RESERVATIONS));
    ReservedList persistedList = ReservedListDao.getLatestRevision("testlist").get();
    assertThat(persistedList.getRevisionId()).isNotNull();
    assertThat(persistedList.getCreationTimestamp()).isEqualTo(jpaRule.getTxnClock().nowUtc());
    assertThat(persistedList.getName()).isEqualTo("testlist");
    assertThat(persistedList.getShouldPublish()).isFalse();
    assertThat(persistedList.getLabelsToReservations()).containsExactlyEntriesIn(TEST_RESERVATIONS);
  }

  @Test
  public void getLatestRevisionCached_worksSuccessfully() {
    ReservedListDao.save(ReservedList.create("testlist", false, TEST_RESERVATIONS));
    ReservedList persistedList = ReservedListDao.getLatestRevisionCached("testlist").get();
    assertThat(persistedList.getRevisionId()).isNotNull();
    assertThat(persistedList.getCreationTimestamp()).isEqualTo(jpaRule.getTxnClock().nowUtc());
    assertThat(persistedList.getName()).isEqualTo("testlist");
    assertThat(persistedList.getShouldPublish()).isFalse();
    assertThat(persistedList.getLabelsToReservations()).containsExactlyEntriesIn(TEST_RESERVATIONS);
  }
}
