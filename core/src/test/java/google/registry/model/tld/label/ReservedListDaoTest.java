// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.tld.label;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableMap;
import google.registry.model.tld.label.ReservedList.ReservedListEntry;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.testing.FakeClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ReservedListDao}. */
public class ReservedListDaoTest {

  private final FakeClock fakeClock = new FakeClock();

  @RegisterExtension
  JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

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
            .setCreationTimestamp(fakeClock.nowUtc())
            .setReservedListMap(testReservations)
            .build();
  }

  @Test
  void save_worksSuccessfully() {
    ReservedListDao.save(testReservedList);
    tm().transact(
            () -> {
              ReservedList persistedList =
                  tm().query("FROM ReservedList WHERE name = :name", ReservedList.class)
                      .setParameter("name", "testlist")
                      .getSingleResult();
              assertThat(persistedList.getReservedListEntries())
                  .containsExactlyEntriesIn(testReservations);
              assertThat(persistedList.getCreationTimestamp()).isEqualTo(fakeClock.nowUtc());
            });
  }

  @Test
  void delete_worksSuccessfully() {
    ReservedListDao.save(testReservedList);
    assertThat(ReservedListDao.checkExists("testlist")).isTrue();
    ReservedListDao.delete(testReservedList);
    assertThat(ReservedListDao.checkExists("testlist")).isFalse();
  }

  @Test
  void delete_listNotInDatabase() {
    assertThat(ReservedListDao.checkExists("testlist")).isFalse();
    ReservedListDao.delete(testReservedList);
    assertThat(ReservedListDao.checkExists("testlist")).isFalse();
  }

  @Test
  void checkExists_worksSuccessfully() {
    assertThat(ReservedListDao.checkExists("testlist")).isFalse();
    ReservedListDao.save(testReservedList);
    assertThat(ReservedListDao.checkExists("testlist")).isTrue();
  }

  @Test
  void getLatestRevision_worksSuccessfully() {
    assertThat(ReservedListDao.getLatestRevision("testlist").isPresent()).isFalse();
    ReservedListDao.save(testReservedList);
    ReservedList persistedList = ReservedListDao.getLatestRevision("testlist").get();
    assertThat(persistedList.getRevisionId()).isNotNull();
    assertThat(persistedList.getCreationTimestamp()).isEqualTo(fakeClock.nowUtc());
    assertThat(persistedList.getName()).isEqualTo("testlist");
    assertThat(persistedList.getReservedListEntries()).containsExactlyEntriesIn(testReservations);
  }

  @Test
  void getLatestRevision_returnsLatestRevision() {
    ReservedListDao.save(
        new ReservedList.Builder()
            .setName("testlist")
            .setCreationTimestamp(fakeClock.nowUtc())
            .setReservedListMap(
                ImmutableMap.of(
                    "old",
                    ReservedListEntry.create(
                        "old", ReservationType.RESERVED_FOR_SPECIFIC_USE, null)))
            .build());
    ReservedListDao.save(testReservedList);
    ReservedList persistedList = ReservedListDao.getLatestRevision("testlist").get();
    assertThat(persistedList.getRevisionId()).isNotNull();
    assertThat(persistedList.getCreationTimestamp()).isEqualTo(fakeClock.nowUtc());
    assertThat(persistedList.getName()).isEqualTo("testlist");
    assertThat(persistedList.getReservedListEntries()).containsExactlyEntriesIn(testReservations);
  }
}
