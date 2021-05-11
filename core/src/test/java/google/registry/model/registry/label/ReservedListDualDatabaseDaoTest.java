// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import google.registry.model.EntityTestCase;
import google.registry.model.registry.label.ReservedList.ReservedListEntry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ReservedListDualDatabaseDaoTest extends EntityTestCase {

  private ImmutableMap<String, ReservedListEntry> reservations;

  private ReservedList reservedList;

  @BeforeEach
  void setUp() {
    fakeClock.setAutoIncrementByOneMilli();
    reservations =
        ImmutableMap.of(
            "food",
            ReservedListEntry.create("food", ReservationType.RESERVED_FOR_SPECIFIC_USE, null),
            "music",
            ReservedListEntry.create("music", ReservationType.FULLY_BLOCKED, "fully blocked"));

    reservedList =
        new ReservedList.Builder()
            .setName("testlist")
            .setLastUpdateTime(fakeClock.nowUtc())
            .setShouldPublish(false)
            .setReservedListMap(reservations)
            .build();
  }

  @Test
  void testSave_success() {
    ReservedListDualDatabaseDao.save(reservedList);
    Optional<ReservedList> savedList =
        ReservedListDualDatabaseDao.getLatestRevision(reservedList.getName());
    assertThat(savedList.get()).isEqualTo(reservedList);
  }

  @Test
  void testDelete_success() {
    ReservedListDualDatabaseDao.save(reservedList);
    assertThat(ReservedListDualDatabaseDao.getLatestRevision(reservedList.getName()).isPresent())
        .isTrue();
    ReservedListDualDatabaseDao.delete(reservedList);
    assertThat(ReservedListDualDatabaseDao.getLatestRevision(reservedList.getName()).isPresent())
        .isFalse();
  }

  @Test
  void testSaveAndLoad_emptyList() {
    ReservedList list =
        new ReservedList.Builder()
            .setName("empty")
            .setLastUpdateTime(fakeClock.nowUtc())
            .setReservedListMap(ImmutableMap.of())
            .build();
    ReservedListDualDatabaseDao.save(list);
    Optional<ReservedList> savedList = ReservedListDualDatabaseDao.getLatestRevision("empty");
    assertThat(savedList.get()).isEqualTo(list);
  }

  @Test
  void testSave_multipleVersions() {
    ReservedListDualDatabaseDao.save(reservedList);
    assertThat(
            ReservedListDualDatabaseDao.getLatestRevision(reservedList.getName())
                .get()
                .getReservedListEntries())
        .isEqualTo(reservations);
    ImmutableMap<String, ReservedListEntry> newReservations =
        ImmutableMap.of(
            "food",
            ReservedListEntry.create("food", ReservationType.RESERVED_FOR_SPECIFIC_USE, null));
    ReservedList secondList =
        new ReservedList.Builder()
            .setName("testlist2")
            .setLastUpdateTime(fakeClock.nowUtc())
            .setShouldPublish(false)
            .setReservedListMap(newReservations)
            .build();
    ReservedListDualDatabaseDao.save(secondList);
    assertThat(
            ReservedListDualDatabaseDao.getLatestRevision(secondList.getName())
                .get()
                .getReservedListEntries())
        .isEqualTo(newReservations);
  }

  @Test
  void testLoad_unequalLists() {
    ReservedListDualDatabaseDao.save(reservedList);
    ReservedList secondList =
        new ReservedList.Builder()
            .setName(reservedList.name)
            .setLastUpdateTime(fakeClock.nowUtc())
            .setShouldPublish(false)
            .setReservedListMap(
                ImmutableMap.of(
                    "food",
                    ReservedListEntry.create(
                        "food", ReservationType.RESERVED_FOR_SPECIFIC_USE, null)))
            .build();
    ReservedListSqlDao.save(secondList);
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> ReservedListDualDatabaseDao.getLatestRevision(reservedList.getName()));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Domain label music has entry in Datastore, but not in Cloud SQL.");
  }

  @Test
  void testLoad_noListInDatastore() {
    ReservedListSqlDao.save(reservedList);
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> ReservedListDualDatabaseDao.getLatestRevision(reservedList.getName()));
    assertThat(thrown).hasMessageThat().contains("Reserved list in Datastore is empty.");
  }
}
