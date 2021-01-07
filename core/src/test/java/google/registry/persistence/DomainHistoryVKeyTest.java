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

package google.registry.persistence;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.ImmutableObject;
import google.registry.model.common.EntityGroupRoot;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory.DomainHistoryId;
import google.registry.model.reporting.HistoryEntry;
import google.registry.schema.replay.EntityTest.EntityForTesting;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import javax.persistence.Transient;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit test for {@link DomainHistoryVKey}. */
@DualDatabaseTest
class DomainHistoryVKeyTest {

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withOfyTestEntities(TestEntity.class)
          .withJpaUnitTestEntities(TestEntity.class)
          .build();

  @TestOfyAndSql
  void testRestoreSymmetricVKey() {
    Key<HistoryEntry> ofyKey =
        Key.create(Key.create(DomainBase.class, "domainRepoId"), HistoryEntry.class, 10L);
    DomainHistoryVKey domainHistoryVKey = DomainHistoryVKey.create(ofyKey);
    TestEntity original = new TestEntity(domainHistoryVKey);
    tm().transact(() -> tm().insert(original));
    TestEntity persisted = tm().transact(() -> tm().loadByKey(original.createVKey()));
    assertThat(persisted).isEqualTo(original);
    // Double check that the persisted.domainHistoryVKey is a symmetric VKey
    assertThat(persisted.domainHistoryVKey.createOfyKey())
        .isEqualTo(
            Key.create(Key.create(DomainBase.class, "domainRepoId"), HistoryEntry.class, 10L));
    assertThat(persisted.domainHistoryVKey.createSqlKey())
        .isEqualTo(new DomainHistoryId("domainRepoId", 10L));
    assertThat(persisted.domainHistoryVKey.createVKey())
        .isEqualTo(
            VKey.create(
                HistoryEntry.class,
                new DomainHistoryId("domainRepoId", 10L),
                Key.create(Key.create(DomainBase.class, "domainRepoId"), HistoryEntry.class, 10L)));
  }

  @TestOfyAndSql
  void testCreateSymmetricVKeyFromOfyKey() {
    Key<HistoryEntry> ofyKey =
        Key.create(Key.create(DomainBase.class, "domainRepoId"), HistoryEntry.class, 10L);
    DomainHistoryVKey domainHistoryVKey = DomainHistoryVKey.create(ofyKey);
    assertThat(domainHistoryVKey.createOfyKey()).isEqualTo(ofyKey);
    assertThat(domainHistoryVKey.createSqlKey())
        .isEqualTo(new DomainHistoryId("domainRepoId", 10L));
    assertThat(domainHistoryVKey.createVKey())
        .isEqualTo(
            VKey.create(HistoryEntry.class, new DomainHistoryId("domainRepoId", 10L), ofyKey));
  }

  @EntityForTesting
  @Entity
  @javax.persistence.Entity(name = "TestEntity")
  private static class TestEntity extends ImmutableObject {
    @Transient @Parent Key<EntityGroupRoot> parent = getCrossTldKey();

    @Id @javax.persistence.Id String id = "id";

    DomainHistoryVKey domainHistoryVKey;

    TestEntity() {}

    TestEntity(DomainHistoryVKey domainHistoryVKey) {
      this.domainHistoryVKey = domainHistoryVKey;
    }

    VKey<TestEntity> createVKey() {
      return VKey.create(TestEntity.class, id, Key.create(parent, TestEntity.class, id));
    }
  }
}
