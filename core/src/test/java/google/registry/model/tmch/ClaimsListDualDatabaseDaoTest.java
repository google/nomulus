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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import google.registry.config.RegistryEnvironment;
import google.registry.model.EntityTestCase;
import google.registry.testing.SystemPropertyExtension;
import org.joda.time.Duration;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ClaimsListDualDatabaseDao}. */
public class ClaimsListDualDatabaseDaoTest extends EntityTestCase {

  @RegisterExtension
  @Order(value = Integer.MAX_VALUE)
  final SystemPropertyExtension systemPropertyExtension = new SystemPropertyExtension();

  @Test
  void testGetList_missingOfy() {
    ClaimsListSqlDao.save(createClaimsList());
    assertThat(assertThrows(IllegalStateException.class, ClaimsListDualDatabaseDao::get))
        .hasMessageThat()
        .isEqualTo("Claims list found in Cloud SQL but not in Datastore.");
  }

  @Test
  void testGetList_fromSql_different() {
    ClaimsListShard.create(fakeClock.nowUtc(), ImmutableMap.of("foo", "bar")).saveToDatastore();
    ClaimsListSqlDao.save(createClaimsList());
    assertThat(assertThrows(IllegalStateException.class, ClaimsListDualDatabaseDao::get))
        .hasMessageThat()
        .isEqualTo(
            "Unequal claims lists detected:\n"
                + "Domain label label1 with key key1 only appears in Cloud SQL.\n"
                + "Domain label label2 with key key2 only appears in Cloud SQL.\n"
                + "Domain label foo with key bar only appears in Datastore.\n");
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
