// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.smd;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import google.registry.model.EntityTestCase;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationWithCoverageExtension;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import google.registry.testing.TestOfyAndSql;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;

@DualDatabaseTest
public class SignedMarkRevocationListDaoTest extends EntityTestCase {

  private final FakeClock clock = new FakeClock();

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestRules.Builder().withClock(clock).buildIntegrationWithCoverageExtension();

  @RegisterExtension
  @Order(value = 1)
  final DatastoreEntityExtension datastoreEntityExtension = new DatastoreEntityExtension();

  @TestOfyAndSql
  void testSave_datastorePrimary_success() {
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(
            clock.nowUtc(), ImmutableMap.of("mark", clock.nowUtc().minusHours(1)));
    SignedMarkRevocationListDao.save("Datastore", list);
    SignedMarkRevocationList fromDb = SignedMarkRevocationListDao.load("Datastore");
    assertAboutImmutableObjects().that(fromDb).isEqualExceptFields(list, "revisionId");
  }

  @TestOfyAndSql
  void testSave_cloudSqlPrimary_success() {
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(
            clock.nowUtc(), ImmutableMap.of("mark", clock.nowUtc().minusHours(1)));
    SignedMarkRevocationListDao.save("Cloud SQL", list);
    SignedMarkRevocationList fromDb = SignedMarkRevocationListDao.load("Cloud SQL");
    assertAboutImmutableObjects().that(fromDb).isEqualExceptFields(list);
  }

  @TestOfyAndSql
  void testSaveAndLoad_datastorePrimary_emptyList() {
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(clock.nowUtc(), ImmutableMap.of());
    SignedMarkRevocationListDao.save("Cloud SQL", list);
    SignedMarkRevocationList fromDb = SignedMarkRevocationListDao.load("Cloud SQL");
    assertAboutImmutableObjects().that(fromDb).isEqualExceptFields(list);
  }

  @TestOfyAndSql
  void testSaveAndLoad_cloudSqlPrimary_emptyList() {
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(clock.nowUtc(), ImmutableMap.of());
    SignedMarkRevocationListDao.save("Datastore", list);
    SignedMarkRevocationList fromDb = SignedMarkRevocationListDao.load("Datastore");
    assertAboutImmutableObjects().that(fromDb).isEqualExceptFields(list, "revisionId");
  }

  @TestOfyAndSql
  void testSave_datastorePrimary_multipleVersions() {
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(
            clock.nowUtc(), ImmutableMap.of("mark", clock.nowUtc().minusHours(1)));
    SignedMarkRevocationListDao.save("Datastore", list);
    assertThat(SignedMarkRevocationListDao.load("Datastore").isSmdRevoked("mark", clock.nowUtc()))
        .isTrue();

    // Now remove the revocation
    SignedMarkRevocationList secondList =
        SignedMarkRevocationList.create(clock.nowUtc(), ImmutableMap.of());
    SignedMarkRevocationListDao.save("Datastore", secondList);
    assertThat(SignedMarkRevocationListDao.load("Datastore").isSmdRevoked("mark", clock.nowUtc()))
        .isFalse();
  }

  @TestOfyAndSql
  void testSave_cloudSqlPrimary_multipleVersions() {
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(
            clock.nowUtc(), ImmutableMap.of("mark", clock.nowUtc().minusHours(1)));
    SignedMarkRevocationListDao.save("Cloud SQL", list);
    assertThat(SignedMarkRevocationListDao.load("Cloud SQL").isSmdRevoked("mark", clock.nowUtc()))
        .isTrue();

    // Now remove the revocation
    SignedMarkRevocationList secondList =
        SignedMarkRevocationList.create(clock.nowUtc(), ImmutableMap.of());
    SignedMarkRevocationListDao.save("Cloud SQL", secondList);
    assertThat(SignedMarkRevocationListDao.load("Cloud SQL").isSmdRevoked("mark", clock.nowUtc()))
        .isFalse();
  }

  @TestOfyAndSql
  void testLoad_datastorePrimary_unequalLists() {
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(
            clock.nowUtc(), ImmutableMap.of("mark", clock.nowUtc().minusHours(1)));
    SignedMarkRevocationListDao.save("Datastore", list);
    SignedMarkRevocationList list2 =
        SignedMarkRevocationList.create(
            clock.nowUtc(), ImmutableMap.of("mark", clock.nowUtc().minusHours(3)));
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(list2));
    RuntimeException thrown =
        assertThrows(RuntimeException.class, () -> SignedMarkRevocationListDao.load("Datastore"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "SMD mark has key 1969-12-31T23:00:00.000Z in primary database and key"
                + " 1969-12-31T21:00:00.000Z in secondary database.");
  }

  @TestOfyAndSql
  void testLoad_cloudSqlPrimary_unequalLists() {
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(
            clock.nowUtc(), ImmutableMap.of("mark", clock.nowUtc().minusHours(1)));
    SignedMarkRevocationListDao.save("Cloud SQL", list);
    SignedMarkRevocationList list2 =
        SignedMarkRevocationList.create(
            clock.nowUtc(), ImmutableMap.of("mark", clock.nowUtc().minusHours(3)));
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(list2));
    RuntimeException thrown =
        assertThrows(RuntimeException.class, () -> SignedMarkRevocationListDao.load("Cloud SQL"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "SMD mark has key 1969-12-31T21:00:00.000Z in primary database and key"
                + " 1969-12-31T23:00:00.000Z in secondary database.");
  }
}
