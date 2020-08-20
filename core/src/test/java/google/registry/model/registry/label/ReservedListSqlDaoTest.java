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

package google.registry.model.registry.label;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableMap;
import google.registry.model.registry.label.ReservedList.ReservedListEntry;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationWithCoverageExtension;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ReservedListSqlDao}. */
public class ReservedListSqlDaoTest {

  private final FakeClock fakeClock = new FakeClock();

  @RegisterExtension
  @Order(value = 1)
  DatastoreEntityExtension datastoreEntityExtension = new DatastoreEntityExtension();

  @RegisterExtension
  JpaIntegrationWithCoverageExtension jpa =
      new JpaTestRules.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  private ImmutableMap<String, ReservedListEntry> testReservations;

  private ReservedList testReservedList;

  @BeforeEach
  void setUp() {
    testReservations =
        ImmutableMap.of(
            "food",
            ReservedListEntry.create("food", ReservationType.RESERVED_FOR_SPECIFIC_USE, null),
            "music",
            ReservedListEntry.create("music", ReservationType.FULLY_BLOCKED, "fully blocked"));

    testReservedList =
        new ReservedList.Builder()
            .setName("testlist")
            .setLastUpdateTime(fakeClock.nowUtc())
            .setShouldPublish(false)
            .setReservedListMap(testReservations)
            .build();
  }

  @Test
  void save_worksSuccessfully() {
    ReservedListSqlDao.save(testReservedList);
    jpaTm()
        .transact(
            () -> {
              ReservedList persistedList =
                  jpaTm()
                      .getEntityManager()
                      .createQuery("FROM ReservedList WHERE name = :name", ReservedList.class)
                      .setParameter("name", "testlist")
                      .getSingleResult();
              assertThat(persistedList.getReservedListEntries())
                  .containsExactlyEntriesIn(testReservations);
              assertThat(persistedList.getLastUpdateTime()).isEqualTo(fakeClock.nowUtc());
            });
  }

  @Test
  void checkExists_worksSuccessfully() {
    assertThat(ReservedListSqlDao.checkExists("testlist")).isFalse();
    ReservedListSqlDao.save(testReservedList);
    assertThat(ReservedListSqlDao.checkExists("testlist")).isTrue();
  }

  @Test
  void getLatestRevision_worksSuccessfully() {
    assertThat(ReservedListSqlDao.getLatestRevision("testlist").isPresent()).isFalse();
    ReservedListSqlDao.save(testReservedList);
    ReservedList persistedList = ReservedListSqlDao.getLatestRevision("testlist").get();
    assertThat(persistedList.getRevisionId()).isNotNull();
    assertThat(persistedList.getLastUpdateTime()).isEqualTo(fakeClock.nowUtc());
    assertThat(persistedList.getName()).isEqualTo("testlist");
    assertThat(persistedList.getShouldPublish()).isFalse();
    assertThat(persistedList.getReservedListEntries()).containsExactlyEntriesIn(testReservations);
  }

  @Test
  void getLatestRevision_returnsLatestRevision() {
    ReservedListSqlDao.save(
        new ReservedList.Builder()
            .setName("testlist")
            .setLastUpdateTime(fakeClock.nowUtc())
            .setShouldPublish(false)
            .setReservedListMap(
                ImmutableMap.of(
                    "old",
                    ReservedListEntry.create(
                        "old", ReservationType.RESERVED_FOR_SPECIFIC_USE, null)))
            .build());
    ReservedListSqlDao.save(testReservedList);
    ReservedList persistedList = ReservedListSqlDao.getLatestRevision("testlist").get();
    assertThat(persistedList.getRevisionId()).isNotNull();
    assertThat(persistedList.getLastUpdateTime()).isEqualTo(fakeClock.nowUtc());
    assertThat(persistedList.getName()).isEqualTo("testlist");
    assertThat(persistedList.getShouldPublish()).isFalse();
    assertThat(persistedList.getReservedListEntries()).containsExactlyEntriesIn(testReservations);
  }
}
