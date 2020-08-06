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

package google.registry.persistence.transaction;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import google.registry.model.ImmutableObject;
import google.registry.model.ofy.DatastoreTransactionManager;
import google.registry.model.ofy.Ofy;
import google.registry.persistence.VKey;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Unit tests for common APIs in {@link DatastoreTransactionManager} and {@link
 * JpaTransactionManagerImpl}.
 */
@DualDatabaseTest
public class TransactionManagerTest {

  private final FakeClock fakeClock = new FakeClock();

  private final TestEntity theEntity = new TestEntity("theEntity", "foo");
  private final ImmutableList<TestEntity> moreEntities =
      ImmutableList.of(
          new TestEntity("entity1", "foo"),
          new TestEntity("entity2", "bar"),
          new TestEntity("entity3", "qux"));

  @RegisterExtension public InjectExtension inject = new InjectExtension();

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withClock(fakeClock)
          .withDatastoreAndCloudSql()
          .withOfyTestEntities(TestEntity.class)
          .withJpaUnitTestEntities(TestEntity.class)
          .build();

  TransactionManagerTest() {}

  @BeforeEach
  void setUp() {
    inject.setStaticField(Ofy.class, "clock", fakeClock);
    fakeClock.advanceOneMilli();
  }

  @TestTemplate
  void inTransaction_returnsCorrespondingResult() {
    assertThat(tm().inTransaction()).isFalse();
    tm().transact(() -> assertThat(tm().inTransaction()).isTrue());
    assertThat(tm().inTransaction()).isFalse();
  }

  @TestTemplate
  void assertInTransaction_throwsExceptionWhenNotInTransaction() {
    assertThrows(IllegalStateException.class, () -> tm().assertInTransaction());
    tm().transact(() -> tm().assertInTransaction());
    assertThrows(IllegalStateException.class, () -> tm().assertInTransaction());
  }

  @TestTemplate
  void getTransactionTime_throwsExceptionWhenNotInTransaction() {
    assertThrows(IllegalStateException.class, () -> tm().getTransactionTime());
    tm().transact(() -> assertThat(tm().getTransactionTime()).isEqualTo(fakeClock.nowUtc()));
    assertThrows(IllegalStateException.class, () -> tm().getTransactionTime());
  }

  @TestTemplate
  void transact_hasNoEffectWithPartialSuccess() {
    assertEntityNotExist(theEntity);
    assertThrows(
        RuntimeException.class,
        () ->
            tm().transact(
                    () -> {
                      tm().saveNew(theEntity);
                      throw new RuntimeException();
                    }));
    assertEntityNotExist(theEntity);
  }

  @TestTemplate
  void transact_reusesExistingTransaction() {
    assertEntityNotExist(theEntity);
    tm().transact(() -> tm().transact(() -> tm().saveNew(theEntity)));
    assertEntityExists(theEntity);
  }

  @TestTemplate
  void transactNew_succeeds() {
    assertEntityNotExist(theEntity);
    tm().transactNew(() -> tm().saveNew(theEntity));
    assertEntityExists(theEntity);
  }

  @TestTemplate
  void transactNewReadOnly_succeeds() {
    assertEntityNotExist(theEntity);
    tm().transact(() -> tm().saveNew(theEntity));
    assertEntityExists(theEntity);
    TestEntity persisted = tm().transactNewReadOnly(() -> tm().load(theEntity.key()));
    assertThat(persisted).isEqualTo(theEntity);
  }

  @TestTemplate
  void transactNewReadOnly_throwsWhenWritingEntity() {
    assertEntityNotExist(theEntity);
    assertThrows(
        RuntimeException.class, () -> tm().transactNewReadOnly(() -> tm().saveNew(theEntity)));
    assertEntityNotExist(theEntity);
  }

  @TestTemplate
  void saveNew_succeeds() {
    assertEntityNotExist(theEntity);
    tm().transact(() -> tm().saveNew(theEntity));
    assertEntityExists(theEntity);
    assertThat(tm().transact(() -> tm().load(theEntity.key()))).isEqualTo(theEntity);
  }

  @TestTemplate
  void saveAllNew_succeeds() {
    assertAllEntitiesNotExist(moreEntities);
    tm().transact(() -> tm().saveAllNew(moreEntities));
    assertAllEntitiesExist(moreEntities);
  }

  @TestTemplate
  void saveNewOrUpdate_persistsNewEntity() {
    assertEntityNotExist(theEntity);
    tm().transact(() -> tm().saveNewOrUpdate(theEntity));
    assertEntityExists(theEntity);
    assertThat(tm().transact(() -> tm().load(theEntity.key()))).isEqualTo(theEntity);
  }

  @TestTemplate
  void saveNewOrUpdate_updatesExistingEntity() {
    tm().transact(() -> tm().saveNew(theEntity));
    TestEntity persisted = tm().transact(() -> tm().load(theEntity.key()));
    assertThat(persisted.data).isEqualTo("foo");
    theEntity.data = "bar";
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().saveNewOrUpdate(theEntity));
    persisted = tm().transact(() -> tm().load(theEntity.key()));
    assertThat(persisted.data).isEqualTo("bar");
  }

  @TestTemplate
  void saveNewOrUpdateAll_succeeds() {
    assertAllEntitiesNotExist(moreEntities);
    tm().transact(() -> tm().saveNewOrUpdateAll(moreEntities));
    assertAllEntitiesExist(moreEntities);
  }

  @TestTemplate
  void update_succeeds() {
    tm().transact(() -> tm().saveNew(theEntity));
    TestEntity persisted =
        tm().transact(
                () ->
                    tm().load(
                            VKey.create(TestEntity.class, theEntity.name, Key.create(theEntity))));
    assertThat(persisted.data).isEqualTo("foo");
    theEntity.data = "bar";
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().update(theEntity));
    persisted = tm().transact(() -> tm().load(theEntity.key()));
    assertThat(persisted.data).isEqualTo("bar");
  }

  @TestTemplate
  void load_succeeds() {
    assertEntityNotExist(theEntity);
    tm().transact(() -> tm().saveNew(theEntity));
    TestEntity persisted = tm().transact(() -> tm().load(theEntity.key()));
    assertThat(persisted.name).isEqualTo("theEntity");
    assertThat(persisted.data).isEqualTo("foo");
  }

  @TestTemplate
  void load_throwsOnMissingElement() {
    assertEntityNotExist(theEntity);
    assertThrows(
        NoSuchElementException.class, () -> tm().transact(() -> tm().load(theEntity.key())));
  }

  @TestTemplate
  void maybeLoad_succeeds() {
    assertEntityNotExist(theEntity);
    tm().transact(() -> tm().saveNew(theEntity));
    TestEntity persisted = tm().transact(() -> tm().maybeLoad(theEntity.key()).get());
    assertThat(persisted.name).isEqualTo("theEntity");
    assertThat(persisted.data).isEqualTo("foo");
  }

  @TestTemplate
  void maybeLoad_nonExistentObject() {
    assertEntityNotExist(theEntity);
    assertThat(tm().transact(() -> tm().maybeLoad(theEntity.key())).isPresent()).isFalse();
  }

  @TestTemplate
  void delete_succeeds() {
    tm().transact(() -> tm().saveNew(theEntity));
    assertEntityExists(theEntity);
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().delete(theEntity.key()));
    assertEntityNotExist(theEntity);
  }

  @TestTemplate
  void delete_doNothingWhenEntityNotExist() {
    assertEntityNotExist(theEntity);
    tm().transact(() -> tm().delete(theEntity.key()));
    assertEntityNotExist(theEntity);
  }

  @TestTemplate
  void delete_succeedsForEntitySet() {
    assertAllEntitiesNotExist(moreEntities);
    tm().transact(() -> tm().saveAllNew(moreEntities));
    Set<VKey<TestEntity>> keys =
        moreEntities.stream().map(TestEntity::key).collect(toImmutableSet());
    assertAllEntitiesExist(moreEntities);
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().delete(keys));
    assertAllEntitiesNotExist(moreEntities);
  }

  @TestTemplate
  void delete_ignoreNonExistentEntity() {
    assertAllEntitiesNotExist(moreEntities);
    tm().transact(() -> tm().saveAllNew(moreEntities));
    List<VKey<TestEntity>> keys =
        moreEntities.stream().map(TestEntity::key).collect(toImmutableList());
    assertAllEntitiesExist(moreEntities);
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().delete(keys.get(0)));
    assertEntityNotExist(moreEntities.get(0));
    fakeClock.advanceOneMilli();
    tm().transact(() -> tm().delete(keys));
    assertAllEntitiesNotExist(moreEntities);
  }

  @TestTemplate
  void load_multi() {
    assertAllEntitiesNotExist(moreEntities);
    tm().transact(() -> tm().saveAllNew(moreEntities));
    List<VKey<TestEntity>> keys =
        moreEntities.stream().map(TestEntity::key).collect(toImmutableList());
    assertThat(tm().transact(() -> tm().load(keys)))
        .isEqualTo(Maps.uniqueIndex(moreEntities, TestEntity::key));
  }

  @TestTemplate
  void load_multiWithDuplicateKeys() {
    assertAllEntitiesNotExist(moreEntities);
    tm().transact(() -> tm().saveAllNew(moreEntities));
    ImmutableList<VKey<TestEntity>> keys =
        moreEntities.stream().map(TestEntity::key).collect(toImmutableList());
    ImmutableList<VKey<TestEntity>> doubleKeys =
        Stream.concat(keys.stream(), keys.stream()).collect(toImmutableList());
    assertThat(tm().transact(() -> tm().load(doubleKeys)))
        .isEqualTo(Maps.uniqueIndex(moreEntities, TestEntity::key));
  }

  @TestTemplate
  void load_multiMissingKeys() {
    assertAllEntitiesNotExist(moreEntities);
    tm().transact(() -> tm().saveAllNew(moreEntities));
    List<VKey<TestEntity>> keys =
        Stream.concat(moreEntities.stream(), Stream.of(new TestEntity("dark", "matter")))
            .map(TestEntity::key)
            .collect(toImmutableList());
    assertThat(tm().transact(() -> tm().load(keys)))
        .isEqualTo(Maps.uniqueIndex(moreEntities, TestEntity::key));
  }

  private static void assertEntityExists(TestEntity entity) {
    assertThat(tm().transact(() -> tm().checkExists(entity))).isTrue();
  }

  private static void assertEntityNotExist(TestEntity entity) {
    assertThat(tm().transact(() -> tm().checkExists(entity))).isFalse();
  }

  private static void assertAllEntitiesExist(ImmutableList<TestEntity> entities) {
    entities.forEach(TransactionManagerTest::assertEntityExists);
  }

  private static void assertAllEntitiesNotExist(ImmutableList<TestEntity> entities) {
    entities.forEach(TransactionManagerTest::assertEntityNotExist);
  }

  @Entity(name = "TxnMgrTestEntity")
  @javax.persistence.Entity(name = "TestEntity")
  private static class TestEntity extends ImmutableObject {
    @Id @javax.persistence.Id private String name;

    private String data;

    private TestEntity() {}

    private TestEntity(String name, String data) {
      this.name = name;
      this.data = data;
    }

    public VKey<TestEntity> key() {
      return VKey.create(TestEntity.class, name, Key.create(this));
    }
  }
}
