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

package google.registry.persistence.transaction;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import google.registry.model.ImmutableObject;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import google.registry.testing.FakeClock;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for APIs in {@link JpaTransactionManagerImpl}. */
public class TransactionManagerTest {

  private final FakeClock fakeClock = new FakeClock();

  private final TestEntity theEntity = new TestEntity("theEntity", "foo");
  private final ImmutableList<TestEntity> moreEntities =
      ImmutableList.of(
          new TestEntity("entity1", "foo"),
          new TestEntity("entity2", "bar"),
          new TestEntity("entity3", "qux"));

  @RegisterExtension
  public final JpaUnitTestExtension jpa =
      new JpaTestExtensions.Builder()
          .withClock(fakeClock)
          .withEntityClass(TestEntity.class, TestEntityBase.class)
          .buildUnitTestExtension();

  TransactionManagerTest() {}

  @BeforeEach
  void setUp() {
    fakeClock.setAutoIncrementByOneMilli();
  }

  @Test
  void inTransaction_returnsCorrespondingResult() {
    assertThat(tm().inTransaction()).isFalse();
    tm().transact(() -> assertThat(tm().inTransaction()).isTrue());
    assertThat(tm().inTransaction()).isFalse();
  }

  @Test
  void assertInTransaction_throwsExceptionWhenNotInTransaction() {
    assertThrows(IllegalStateException.class, () -> tm().assertInTransaction());
    tm().transact(() -> tm().assertInTransaction());
    assertThrows(IllegalStateException.class, () -> tm().assertInTransaction());
  }

  @Test
  void getTransactionTime_throwsExceptionWhenNotInTransaction() {
    assertThrows(IllegalStateException.class, () -> tm().getTransactionTime());
    fakeClock.disableAutoIncrement();
    tm().transact(() -> assertThat(tm().getTransactionTime()).isEqualTo(fakeClock.nowUtc()));
    assertThrows(IllegalStateException.class, () -> tm().getTransactionTime());
  }

  @Test
  void transact_hasNoEffectWithPartialSuccess() {
    assertEntityNotExist(theEntity);
    assertThrows(
        RuntimeException.class,
        () ->
            tm().transact(
                    () -> {
                      tm().insert(theEntity);
                      throw new RuntimeException();
                    }));
    assertEntityNotExist(theEntity);
  }

  @Test
  void transact_reusesExistingTransaction() {
    assertEntityNotExist(theEntity);
    tm().transact(() -> tm().transact(() -> tm().insert(theEntity)));
    assertEntityExists(theEntity);
  }

  @Test
  void saveNew_succeeds() {
    assertEntityNotExist(theEntity);
    tm().transact(() -> tm().insert(theEntity));
    assertEntityExists(theEntity);
    assertThat(tm().transact(() -> tm().loadByKey(theEntity.createVKey()))).isEqualTo(theEntity);
  }

  @Test
  void saveAllNew_succeeds() {
    assertAllEntitiesNotExist(moreEntities);
    tm().transact(() -> tm().insertAll(moreEntities));
    assertAllEntitiesExist(moreEntities);
  }

  @Test
  void saveNewOrUpdate_persistsNewEntity() {
    assertEntityNotExist(theEntity);
    tm().transact(() -> tm().put(theEntity));
    assertEntityExists(theEntity);
    assertThat(tm().transact(() -> tm().loadByKey(theEntity.createVKey()))).isEqualTo(theEntity);
  }

  @Test
  void saveNewOrUpdate_updatesExistingEntity() {
    tm().transact(() -> tm().insert(theEntity));
    TestEntity persisted = tm().transact(() -> tm().loadByKey(theEntity.createVKey()));
    assertThat(persisted.data).isEqualTo("foo");
    theEntity.data = "bar";
    tm().transact(() -> tm().put(theEntity));
    persisted = tm().transact(() -> tm().loadByKey(theEntity.createVKey()));
    assertThat(persisted.data).isEqualTo("bar");
  }

  @Test
  void saveNewOrUpdateAll_succeeds() {
    assertAllEntitiesNotExist(moreEntities);
    tm().transact(() -> tm().putAll(moreEntities));
    assertAllEntitiesExist(moreEntities);
  }

  @Test
  void update_succeeds() {
    tm().transact(() -> tm().insert(theEntity));
    TestEntity persisted =
        tm().transact(() -> tm().loadByKey(VKey.create(TestEntity.class, theEntity.name)));
    assertThat(persisted.data).isEqualTo("foo");
    theEntity.data = "bar";
    tm().transact(() -> tm().update(theEntity));
    persisted = tm().transact(() -> tm().loadByKey(theEntity.createVKey()));
    assertThat(persisted.data).isEqualTo("bar");
  }

  @Test
  void load_succeeds() {
    assertEntityNotExist(theEntity);
    tm().transact(() -> tm().insert(theEntity));
    TestEntity persisted = tm().transact(() -> tm().loadByKey(theEntity.createVKey()));
    assertThat(persisted.name).isEqualTo("theEntity");
    assertThat(persisted.data).isEqualTo("foo");
  }

  @Test
  void load_throwsOnMissingElement() {
    assertEntityNotExist(theEntity);
    assertThrows(
        NoSuchElementException.class,
        () -> tm().transact(() -> tm().loadByKey(theEntity.createVKey())));
  }

  @Test
  void maybeLoad_succeeds() {
    assertEntityNotExist(theEntity);
    tm().transact(() -> tm().insert(theEntity));
    TestEntity persisted =
        tm().transact(() -> tm().loadByKeyIfPresent(theEntity.createVKey()).get());
    assertThat(persisted.name).isEqualTo("theEntity");
    assertThat(persisted.data).isEqualTo("foo");
  }

  @Test
  void maybeLoad_nonExistentObject() {
    assertEntityNotExist(theEntity);
    assertThat(tm().transact(() -> tm().loadByKeyIfPresent(theEntity.createVKey())).isPresent())
        .isFalse();
  }

  @Test
  void delete_succeeds() {
    tm().transact(() -> tm().insert(theEntity));
    assertEntityExists(theEntity);
    tm().transact(() -> tm().delete(theEntity.createVKey()));
    assertEntityNotExist(theEntity);
  }

  @Test
  void delete_doNothingWhenEntityNotExist() {
    assertEntityNotExist(theEntity);
    tm().transact(() -> tm().delete(theEntity.createVKey()));
    assertEntityNotExist(theEntity);
  }

  @Test
  void delete_succeedsForEntitySet() {
    assertAllEntitiesNotExist(moreEntities);
    tm().transact(() -> tm().insertAll(moreEntities));
    Set<VKey<TestEntity>> keys =
        moreEntities.stream().map(TestEntity::createVKey).collect(toImmutableSet());
    assertAllEntitiesExist(moreEntities);
    tm().transact(() -> tm().delete(keys));
    assertAllEntitiesNotExist(moreEntities);
  }

  @Test
  void delete_ignoreNonExistentEntity() {
    assertAllEntitiesNotExist(moreEntities);
    tm().transact(() -> tm().insertAll(moreEntities));
    List<VKey<TestEntity>> keys =
        moreEntities.stream().map(TestEntity::createVKey).collect(toImmutableList());
    assertAllEntitiesExist(moreEntities);
    tm().transact(() -> tm().delete(keys.get(0)));
    assertEntityNotExist(moreEntities.get(0));
    tm().transact(() -> tm().delete(keys));
    assertAllEntitiesNotExist(moreEntities);
  }

  @Test
  void delete_deletesTheGivenEntity() {
    tm().transact(() -> tm().insert(theEntity));
    assertEntityExists(theEntity);
    tm().transact(() -> tm().delete(theEntity));
    assertEntityNotExist(theEntity);
  }

  @Test
  void load_multi() {
    assertAllEntitiesNotExist(moreEntities);
    tm().transact(() -> tm().insertAll(moreEntities));
    List<VKey<TestEntity>> keys =
        moreEntities.stream().map(TestEntity::createVKey).collect(toImmutableList());
    assertThat(tm().transact(() -> tm().loadByKeys(keys)))
        .isEqualTo(Maps.uniqueIndex(moreEntities, TestEntity::createVKey));
  }

  @Test
  void load_multiWithDuplicateKeys() {
    assertAllEntitiesNotExist(moreEntities);
    tm().transact(() -> tm().insertAll(moreEntities));
    ImmutableList<VKey<TestEntity>> keys =
        moreEntities.stream().map(TestEntity::createVKey).collect(toImmutableList());
    ImmutableList<VKey<TestEntity>> doubleKeys =
        Stream.concat(keys.stream(), keys.stream()).collect(toImmutableList());
    assertThat(tm().transact(() -> tm().loadByKeys(doubleKeys)))
        .isEqualTo(Maps.uniqueIndex(moreEntities, TestEntity::createVKey));
  }

  @Test
  void load_multiMissingKeys() {
    assertAllEntitiesNotExist(moreEntities);
    tm().transact(() -> tm().insertAll(moreEntities));
    List<VKey<TestEntity>> keys =
        Stream.concat(moreEntities.stream(), Stream.of(new TestEntity("dark", "matter")))
            .map(TestEntity::createVKey)
            .collect(toImmutableList());
    assertThat(
            assertThrows(
                NoSuchElementException.class, () -> tm().transact(() -> tm().loadByKeys(keys))))
        .hasMessageThat()
        .contains("dark");
  }

  @Test
  void loadExisting_missingKeys() {
    assertAllEntitiesNotExist(moreEntities);
    tm().transact(() -> tm().insertAll(moreEntities));
    List<VKey<TestEntity>> keys =
        Stream.concat(moreEntities.stream(), Stream.of(new TestEntity("dark", "matter")))
            .map(TestEntity::createVKey)
            .collect(toImmutableList());
    assertThat(tm().transact(() -> tm().loadByKeysIfPresent(keys)))
        .isEqualTo(Maps.uniqueIndex(moreEntities, TestEntity::createVKey));
  }

  @Test
  void loadAll_success() {
    tm().transact(() -> tm().insertAll(moreEntities));
    assertThat(tm().transact(() -> tm().loadByEntities(moreEntities)))
        .containsExactlyElementsIn(moreEntities);
  }

  @Test
  void loadAll_missingKeys() {
    assertAllEntitiesNotExist(moreEntities);
    tm().transact(() -> tm().insertAll(moreEntities));
    ImmutableList<TestEntity> nonexistent = ImmutableList.of(new TestEntity("dark", "matter"));
    assertThat(
            assertThrows(
                NoSuchElementException.class,
                () -> tm().transact(() -> tm().loadByEntities(nonexistent))))
        .hasMessageThat()
        .contains("dark");
  }

  @Test
  void loadAllExisting_missingKeys() {
    tm().transact(() -> tm().insertAll(moreEntities));
    tm().transact(() -> tm().delete(new TestEntity("entity1", "foo")));
    assertThat(
            tm().transact(
                    () ->
                        tm().loadByEntitiesIfPresent(moreEntities).stream()
                            .map(TestEntity::createVKey)
                            .map(VKey::getKey)
                            .collect(toImmutableList())))
        .containsExactly("entity2", "entity3");
  }

  @Test
  void loadSingleton_returnsValue_orEmpty() {
    assertEntityNotExist(theEntity);
    assertThat(tm().transact(() -> tm().loadSingleton(TestEntity.class))).isEmpty();

    tm().transact(() -> tm().insert(theEntity));
    assertThat(tm().transact(() -> tm().loadSingleton(TestEntity.class))).hasValue(theEntity);
  }

  @Test
  void loadSingleton_exceptionOnMultiple() {
    assertAllEntitiesNotExist(moreEntities);
    tm().transact(() -> tm().insertAll(moreEntities));
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> tm().transact(() -> tm().loadSingleton(TestEntity.class))))
        .hasMessageThat()
        .isEqualTo("Expected at most one entity of type TestEntity, found at least two");
  }

  @Test
  void mutatedObjectNotPersisted() {
    tm().transact(() -> tm().insert(theEntity));
    tm().transact(
            () -> {
              TestEntity e = tm().loadByKey(theEntity.createVKey());
              e.data = "some other data!";
            });
    assertThat(tm().transact(() -> tm().loadByKey(theEntity.createVKey())).data).isEqualTo("foo");
  }

  private static void assertEntityExists(TestEntity entity) {
    assertThat(tm().transact(() -> tm().exists(entity))).isTrue();
  }

  private static void assertEntityNotExist(TestEntity entity) {
    assertThat(tm().transact(() -> tm().exists(entity))).isFalse();
  }

  private static void assertAllEntitiesExist(ImmutableList<TestEntity> entities) {
    entities.forEach(TransactionManagerTest::assertEntityExists);
  }

  private static void assertAllEntitiesNotExist(ImmutableList<TestEntity> entities) {
    entities.forEach(TransactionManagerTest::assertEntityNotExist);
  }

  /**
   * We put the id field into a base class to test that id fields can be discovered in a base class.
   */
  @MappedSuperclass
  @Embeddable
  private static class TestEntityBase extends ImmutableObject {
    @Id protected String name;

    TestEntityBase(String name) {
      this.name = name;
    }

    TestEntityBase() {}
  }

  @Entity(name = "TestEntity")
  private static class TestEntity extends TestEntityBase {

    private String data;

    private TestEntity() {}

    private TestEntity(String name, String data) {
      super(name);
      this.data = data;
    }

    @Override
    public VKey<TestEntity> createVKey() {
      return VKey.create(TestEntity.class, name);
    }
  }
}
