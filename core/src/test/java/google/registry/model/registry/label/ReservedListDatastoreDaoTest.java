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
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;

import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.Key;
import google.registry.model.registry.label.ReservedList.ReservedListEntry;
import google.registry.persistence.VKey;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ReservedListDatastoreDao}. */
public class ReservedListDatastoreDaoTest {

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  private final FakeClock fakeClock = new FakeClock();

  private ImmutableMap<String, ReservedListEntry> reservations;

  private ReservedList reservedList;

  @BeforeEach
  void setUp() {
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
  void save_worksSuccessfully() {
    ReservedListDatastoreDao.save(reservedList);
    Optional<ReservedList> savedList =
        ofyTm()
            .loadByKeyIfPresent(
                VKey.createOfy(
                    ReservedList.class,
                    Key.create(getCrossTldKey(), ReservedList.class, reservedList.name)));
    assertThat(savedList.get()).isEqualTo(reservedList);
  }

  @Test
  void delete_worksSuccessfully() {
    ReservedListDatastoreDao.save(reservedList);
    assertThat(ReservedListDatastoreDao.getLatestRevision("testlist").isPresent()).isTrue();
    ReservedListDatastoreDao.delete(reservedList);
    assertThat(ReservedListDatastoreDao.getLatestRevision("testlist").isPresent()).isFalse();
  }

  @Test
  void delete_listNotInDatastore() {
    assertThat(ReservedListDatastoreDao.getLatestRevision("testlist").isPresent()).isFalse();
    ReservedListDatastoreDao.delete(reservedList);
    assertThat(ReservedListDatastoreDao.getLatestRevision("testlist").isPresent()).isFalse();
  }

  @Test
  void getLatestRevision_worksSuccessfully() {
    assertThat(ReservedListDatastoreDao.getLatestRevision("testlist").isPresent()).isFalse();
    ReservedListDatastoreDao.save(reservedList);
    ReservedList persistedList = ReservedListDatastoreDao.getLatestRevision("testlist").get();
    assertThat(persistedList).isEqualTo(reservedList);
  }

  @Test
  void getLatestRevision_returnsLatestRevision() {
    ReservedListDatastoreDao.save(
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
    assertThat(ReservedListDatastoreDao.getLatestRevision("testlist").get())
        .isNotEqualTo(reservedList);
    ReservedListDatastoreDao.save(reservedList);
    ReservedList persistedList = ReservedListDatastoreDao.getLatestRevision("testlist").get();
    assertThat(persistedList).isEqualTo(reservedList);
  }
}
