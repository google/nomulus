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
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.config.RegistryEnvironment;
import google.registry.model.EntityTestCase;
import google.registry.model.common.DatabaseTransitionSchedule;
import google.registry.model.common.DatabaseTransitionSchedule.PrimaryDatabase;
import google.registry.model.common.DatabaseTransitionSchedule.PrimaryDatabaseTransition;
import google.registry.model.common.DatabaseTransitionSchedule.TransitionId;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.model.registry.label.ReservedList.ReservedListEntry;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.SystemPropertyExtension;
import google.registry.testing.TestOfyAndSql;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

@DualDatabaseTest
public class ReservedListDualDatabaseDaoTest extends EntityTestCase {

  @RegisterExtension
  final SystemPropertyExtension systemPropertyExtension = new SystemPropertyExtension();

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

    fakeClock.setTo(DateTime.parse("1984-12-21T00:00:00.000Z"));
    DatabaseTransitionSchedule schedule =
        DatabaseTransitionSchedule.create(
            TransitionId.DOMAIN_LABEL_LISTS,
            TimedTransitionProperty.fromValueMap(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    PrimaryDatabase.DATASTORE,
                    fakeClock.nowUtc().plusDays(1),
                    PrimaryDatabase.CLOUD_SQL),
                PrimaryDatabaseTransition.class));

    tm().transactNew(() -> ofy().saveWithoutBackup().entity(schedule).now());
  }

  @TestOfyAndSql
  void testSave_datastorePrimary_success() {
    ReservedListDualDatabaseDao.save(reservedList);
    Optional<ReservedList> savedList =
        ReservedListDualDatabaseDao.getLatestRevision(reservedList.getName());
    assertThat(savedList.get()).isEqualTo(reservedList);
  }

  @TestOfyAndSql
  void testSave_CloudSqlPrimary_success() {
    fakeClock.advanceBy(Duration.standardDays(5));
    ReservedListDualDatabaseDao.save(reservedList);
    Optional<ReservedList> savedList =
        ReservedListDualDatabaseDao.getLatestRevision(reservedList.getName());
    assertThat(savedList.get()).isEqualTo(reservedList);
  }

  @TestOfyAndSql
  void testSaveAndLoad_datastorePrimary_emptyList() {
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

  @TestOfyAndSql
  void testSaveAndLoad_cloudSqlPrimary_emptyList() {
    fakeClock.advanceBy(Duration.standardDays(5));
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

  @TestOfyAndSql
  void testSave_datastorePrimary_multipleVersions() {
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

  @TestOfyAndSql
  void testSave_cloudSqlPrimary_multipleVersions() {
    fakeClock.advanceBy(Duration.standardDays(5));
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

  @TestOfyAndSql
  void testLoad_datastorePrimary_unequalLists() {
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
        .contains("Domain label music has entry in Datastore, but not in the secondary database.");
  }

  @TestOfyAndSql
  void testLoad_cloudSqlPrimary_unequalLists() {
    fakeClock.advanceBy(Duration.standardDays(5));
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
        .contains("Domain label music has entry in Datastore, but not in the primary database.");
  }

  @TestOfyAndSql
  void testLoad_cloudSqlPrimary_unequalLists_succeedsInProduction() {
    RegistryEnvironment.PRODUCTION.setup(systemPropertyExtension);
    fakeClock.advanceBy(Duration.standardDays(5));
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
    Optional<ReservedList> savedList =
        ReservedListDualDatabaseDao.getLatestRevision(reservedList.getName());
    assertThat(savedList.get()).isEqualTo(secondList);
  }

  @TestOfyAndSql
  void testLoad_DatastorePrimary_noListInCloudSql() {
    ReservedListDatastoreDao.save(reservedList);
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> ReservedListDualDatabaseDao.getLatestRevision(reservedList.getName()));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Reserved list in the secondary database (Cloud SQL) is empty.");
  }

  @TestOfyAndSql
  void testLoad_cloudSqlPrimary_noListInDatastore() {
    fakeClock.advanceBy(Duration.standardDays(5));
    ReservedListSqlDao.save(reservedList);
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> ReservedListDualDatabaseDao.getLatestRevision(reservedList.getName()));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Reserved list in the secondary database (Datastore) is empty.");
  }
}
