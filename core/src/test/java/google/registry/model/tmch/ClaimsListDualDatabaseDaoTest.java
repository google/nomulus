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

package google.registry.model.tmch;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
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
import google.registry.testing.SystemPropertyExtension;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ClaimsListDualDatabaseDao}. */
public class ClaimsListDualDatabaseDaoTest extends EntityTestCase {

  @RegisterExtension
  @Order(value = Integer.MAX_VALUE)
  final SystemPropertyExtension systemPropertyExtension = new SystemPropertyExtension();

  @BeforeEach
  void beforeEach() {
    DatabaseTransitionSchedule schedule =
        DatabaseTransitionSchedule.create(
            TransitionId.CLAIMS_lIST,
            TimedTransitionProperty.fromValueMap(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    PrimaryDatabase.DATASTORE,
                    fakeClock.nowUtc().plusDays(1),
                    PrimaryDatabase.CLOUD_SQL),
                PrimaryDatabaseTransition.class));
    tm().transactNew(() -> ofy().saveWithoutBackup().entity(schedule).now());
  }

  @Test
  void testGetList_missingSql() {
    createClaimsList().save();
    // The error message is quite long, only print part of it
    assertThat(assertThrows(IllegalStateException.class, ClaimsListDualDatabaseDao::get))
        .hasMessageThat()
        .startsWith("Unequal ClaimsList values from primary Datastore DB");
  }

  @Test
  void testGetList_missingOfy() {
    fakeClock.advanceBy(Duration.standardDays(5));
    ClaimsListSqlDao.save(createClaimsList());
    // The error message is quite long, only print part of it
    assertThat(assertThrows(IllegalStateException.class, ClaimsListDualDatabaseDao::get))
        .hasMessageThat()
        .startsWith("Unequal ClaimsList values from primary SQL DB");
  }

  @Test
  void testGetList_fromOfy_different() {
    createClaimsList().save();
    ClaimsListSqlDao.save(
        ClaimsListShard.create(fakeClock.nowUtc(), ImmutableMap.of("foo", "bar")));
    // The error message is quite long, only print part of it
    assertThat(assertThrows(IllegalStateException.class, ClaimsListDualDatabaseDao::get))
        .hasMessageThat()
        .startsWith("Unequal ClaimsList values from primary Datastore DB");
  }

  @Test
  void testGetList_fromSql_different() {
    fakeClock.advanceBy(Duration.standardDays(5));
    ClaimsListShard.create(fakeClock.nowUtc(), ImmutableMap.of("foo", "bar")).save();
    ClaimsListSqlDao.save(createClaimsList());
    // The error message is quite long, only print part of it
    assertThat(assertThrows(IllegalStateException.class, ClaimsListDualDatabaseDao::get))
        .hasMessageThat()
        .startsWith("Unequal ClaimsList values from primary SQL DB");
  }

  @Test
  void testSaveAndGet() {
    tm().transact(() -> ClaimsListDualDatabaseDao.save(createClaimsList()));
    assertAboutImmutableObjects()
        .that(ClaimsListDualDatabaseDao.get())
        .isEqualExceptFields(createClaimsList(), "id", "revisionId", "creationTimestamp");
  }

  @Test
  void testGet_empty() {
    assertThat(tm().transact(ClaimsListDualDatabaseDao::get).getLabelsToKeys()).isEmpty();
  }

  @Test
  void testGetList_missingSql_notInTest() {
    RegistryEnvironment.PRODUCTION.setup(systemPropertyExtension);
    createClaimsList().save();
    // Shouldn't fail in production
    assertThat(ClaimsListDualDatabaseDao.get().getLabelsToKeys())
        .isEqualTo(createClaimsList().getLabelsToKeys());
  }

  @Test
  void testGetList_missingOfy_notInTest() {
    RegistryEnvironment.PRODUCTION.setup(systemPropertyExtension);
    fakeClock.advanceBy(Duration.standardDays(5));
    ClaimsListSqlDao.save(createClaimsList());
    // Shouldn't fail in production
    assertThat(ClaimsListDualDatabaseDao.get().getLabelsToKeys())
        .isEqualTo(createClaimsList().getLabelsToKeys());
  }

  private ClaimsListShard createClaimsList() {
    return ClaimsListShard.create(
        fakeClock.nowUtc(), ImmutableMap.of("label1", "key1", "label2", "key2"));
  }
}
