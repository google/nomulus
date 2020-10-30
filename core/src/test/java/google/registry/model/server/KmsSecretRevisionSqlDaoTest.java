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

package google.registry.model.server;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationWithCoverageExtension;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import java.util.Optional;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link google.registry.model.server.KmsSecretRevisionSqlDao}. */
public class KmsSecretRevisionSqlDaoTest {

  private final FakeClock fakeClock = new FakeClock();

  @RegisterExtension
  @Order(value = 1)
  DatastoreEntityExtension datastoreEntityExtension = new DatastoreEntityExtension();

  @RegisterExtension
  JpaIntegrationWithCoverageExtension jpa =
      new JpaTestRules.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  @Test
  void testSaveAndRetrieve() {
    KmsSecretRevision revision = createRevision();
    jpaTm().transact(() -> KmsSecretRevisionSqlDao.save(revision));
    Optional<KmsSecretRevision> fromSql =
        jpaTm().transact(() -> KmsSecretRevisionSqlDao.getLatestRevision("secretName"));
    assertThat(fromSql.isPresent()).isTrue();
    assertAboutImmutableObjects().that(revision).isEqualExceptFields(fromSql.get(), "creationTime");
  }

  @Test
  void testMultipleRevisions() {
    KmsSecretRevision revision = createRevision();
    jpaTm().transact(() -> KmsSecretRevisionSqlDao.save(revision));

    KmsSecretRevision secondRevision = createRevision();
    secondRevision.encryptedValue = "someOtherValue";
    jpaTm().transact(() -> KmsSecretRevisionSqlDao.save(secondRevision));

    Optional<KmsSecretRevision> fromSql =
        jpaTm().transact(() -> KmsSecretRevisionSqlDao.getLatestRevision("secretName"));
    assertThat(fromSql.isPresent()).isTrue();
    assertThat(fromSql.get().getEncryptedValue()).isEqualTo("someOtherValue");
  }

  @Test
  void testNonexistent() {
    KmsSecretRevision revision = createRevision();
    jpaTm().transact(() -> KmsSecretRevisionSqlDao.save(revision));
    assertThat(
            jpaTm()
                .transact(() -> KmsSecretRevisionSqlDao.getLatestRevision("someOtherSecretName"))
                .isPresent())
        .isFalse();
  }

  private KmsSecretRevision createRevision() {
    return new KmsSecretRevision.Builder()
        .setEncryptedValue("encrypted")
        .setKmsCryptoKeyVersionName("version")
        .setParent("secretName")
        .build();
  }
}
