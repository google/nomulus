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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.TestDataHelper.fileClassPath;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestExtension;
import google.registry.testing.FakeClock;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.NoSuchElementException;
import javax.persistence.Entity;
import javax.persistence.EntityManager;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.RollbackException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Unit tests for SQL only APIs defined in {@link JpaTransactionManagerImpl}. Note that the tests
 * for common APIs in {@link TransactionManager} are added in {@link TransactionManagerTest}.
 *
 * <p>TODO(shicong): Remove duplicate tests that covered by TransactionManagerTest by refactoring
 * the test schema.
 */
class JpaTransactionManagerImplTest {

  private final FakeClock fakeClock = new FakeClock();
  private final TestEntity theEntity = new TestEntity("theEntity", "foo");
  private final VKey<TestEntity> theEntityKey = VKey.createSql(TestEntity.class, "theEntity");
  private final TestCompoundIdEntity compoundIdEntity =
      new TestCompoundIdEntity("compoundIdEntity", 10, "foo");
  private final VKey<TestCompoundIdEntity> compoundIdEntityKey =
      VKey.createSql(TestCompoundIdEntity.class, new CompoundId("compoundIdEntity", 10));
  private final ImmutableList<TestEntity> moreEntities =
      ImmutableList.of(
          new TestEntity("entity1", "foo"),
          new TestEntity("entity2", "bar"),
          new TestEntity("entity3", "qux"));

  @RegisterExtension
  final JpaUnitTestExtension jpaExtension =
      new JpaTestRules.Builder()
          .withInitScript(fileClassPath(getClass(), "test_schema.sql"))
          .withClock(fakeClock)
          .withEntityClass(TestEntity.class, TestCompoundIdEntity.class)
          .buildUnitTestRule();

  @Test
  void transact_succeeds() {
    assertPersonEmpty();
    assertCompanyEmpty();
    jpaTm()
        .transact(
            () -> {
              insertPerson(10);
              insertCompany("Foo");
              insertCompany("Bar");
            });
    assertPersonCount(1);
    assertPersonExist(10);
    assertCompanyCount(2);
    assertCompanyExist("Foo");
    assertCompanyExist("Bar");
  }

  @Test
  void transact_hasNoEffectWithPartialSuccess() {
    assertPersonEmpty();
    assertCompanyEmpty();
    assertThrows(
        RuntimeException.class,
        () ->
            jpaTm()
                .transact(
                    () -> {
                      insertPerson(10);
                      insertCompany("Foo");
                      throw new RuntimeException();
                    }));
    assertPersonEmpty();
    assertCompanyEmpty();
  }

  @Test
  void transact_reusesExistingTransaction() {
    assertPersonEmpty();
    assertCompanyEmpty();
    jpaTm()
        .transact(
            () ->
                jpaTm()
                    .transact(
                        () -> {
                          insertPerson(10);
                          insertCompany("Foo");
                          insertCompany("Bar");
                        }));
    assertPersonCount(1);
    assertPersonExist(10);
    assertCompanyCount(2);
    assertCompanyExist("Foo");
    assertCompanyExist("Bar");
  }

  @Test
  void saveNew_succeeds() {
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(theEntity))).isFalse();
    jpaTm().transact(() -> jpaTm().saveNew(theEntity));
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(theEntity))).isTrue();
    assertThat(jpaTm().transact(() -> jpaTm().load(theEntityKey))).isEqualTo(theEntity);
  }

  @Test
  void saveNew_throwsExceptionIfEntityExists() {
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(theEntity))).isFalse();
    jpaTm().transact(() -> jpaTm().saveNew(theEntity));
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(theEntity))).isTrue();
    assertThat(jpaTm().transact(() -> jpaTm().load(theEntityKey))).isEqualTo(theEntity);
    assertThrows(RollbackException.class, () -> jpaTm().transact(() -> jpaTm().saveNew(theEntity)));
  }

  @Test
  void createCompoundIdEntity_succeeds() {
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(compoundIdEntity))).isFalse();
    jpaTm().transact(() -> jpaTm().saveNew(compoundIdEntity));
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(compoundIdEntity))).isTrue();
    assertThat(jpaTm().transact(() -> jpaTm().load(compoundIdEntityKey)))
        .isEqualTo(compoundIdEntity);
  }

  @Test
  void saveAllNew_succeeds() {
    moreEntities.forEach(
        entity -> assertThat(jpaTm().transact(() -> jpaTm().checkExists(entity))).isFalse());
    jpaTm().transact(() -> jpaTm().saveAllNew(moreEntities));
    moreEntities.forEach(
        entity -> assertThat(jpaTm().transact(() -> jpaTm().checkExists(entity))).isTrue());
    assertThat(jpaTm().transact(() -> jpaTm().loadAll(TestEntity.class)))
        .containsExactlyElementsIn(moreEntities);
  }

  @Test
  void saveAllNew_rollsBackWhenFailure() {
    moreEntities.forEach(
        entity -> assertThat(jpaTm().transact(() -> jpaTm().checkExists(entity))).isFalse());
    jpaTm().transact(() -> jpaTm().saveNew(moreEntities.get(0)));
    assertThrows(
        RollbackException.class, () -> jpaTm().transact(() -> jpaTm().saveAllNew(moreEntities)));
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(moreEntities.get(0)))).isTrue();
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(moreEntities.get(1)))).isFalse();
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(moreEntities.get(2)))).isFalse();
  }

  @Test
  void saveNewOrUpdate_persistsNewEntity() {
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(theEntity))).isFalse();
    jpaTm().transact(() -> jpaTm().saveNewOrUpdate(theEntity));
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(theEntity))).isTrue();
    assertThat(jpaTm().transact(() -> jpaTm().load(theEntityKey))).isEqualTo(theEntity);
  }

  @Test
  void saveNewOrUpdate_updatesExistingEntity() {
    jpaTm().transact(() -> jpaTm().saveNew(theEntity));
    TestEntity persisted = jpaTm().transact(() -> jpaTm().load(theEntityKey));
    assertThat(persisted.data).isEqualTo("foo");
    theEntity.data = "bar";
    jpaTm().transact(() -> jpaTm().saveNewOrUpdate(theEntity));
    persisted = jpaTm().transact(() -> jpaTm().load(theEntityKey));
    assertThat(persisted.data).isEqualTo("bar");
  }

  @Test
  void saveNewOrUpdateAll_succeeds() {
    moreEntities.forEach(
        entity -> assertThat(jpaTm().transact(() -> jpaTm().checkExists(entity))).isFalse());
    jpaTm().transact(() -> jpaTm().saveNewOrUpdateAll(moreEntities));
    moreEntities.forEach(
        entity -> assertThat(jpaTm().transact(() -> jpaTm().checkExists(entity))).isTrue());
    assertThat(jpaTm().transact(() -> jpaTm().loadAll(TestEntity.class)))
        .containsExactlyElementsIn(moreEntities);
  }

  @Test
  void update_succeeds() {
    jpaTm().transact(() -> jpaTm().saveNew(theEntity));
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().load(VKey.createSql(TestEntity.class, "theEntity")));
    assertThat(persisted.data).isEqualTo("foo");
    theEntity.data = "bar";
    jpaTm().transact(() -> jpaTm().update(theEntity));
    persisted = jpaTm().transact(() -> jpaTm().load(theEntityKey));
    assertThat(persisted.data).isEqualTo("bar");
  }

  @Test
  void updateCompoundIdEntity_succeeds() {
    jpaTm().transact(() -> jpaTm().saveNew(compoundIdEntity));
    TestCompoundIdEntity persisted = jpaTm().transact(() -> jpaTm().load(compoundIdEntityKey));
    assertThat(persisted.data).isEqualTo("foo");
    compoundIdEntity.data = "bar";
    jpaTm().transact(() -> jpaTm().update(compoundIdEntity));
    persisted = jpaTm().transact(() -> jpaTm().load(compoundIdEntityKey));
    assertThat(persisted.data).isEqualTo("bar");
  }

  @Test
  void update_throwsExceptionWhenEntityDoesNotExist() {
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(theEntity))).isFalse();
    assertThrows(
        IllegalArgumentException.class, () -> jpaTm().transact(() -> jpaTm().update(theEntity)));
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(theEntity))).isFalse();
  }

  @Test
  void updateAll_succeeds() {
    jpaTm().transact(() -> jpaTm().saveAllNew(moreEntities));
    ImmutableList<TestEntity> updated =
        ImmutableList.of(
            new TestEntity("entity1", "foo_updated"),
            new TestEntity("entity2", "bar_updated"),
            new TestEntity("entity3", "qux_updated"));
    jpaTm().transact(() -> jpaTm().updateAll(updated));
    assertThat(jpaTm().transact(() -> jpaTm().loadAll(TestEntity.class)))
        .containsExactlyElementsIn(updated);
  }

  @Test
  void updateAll_rollsBackWhenFailure() {
    jpaTm().transact(() -> jpaTm().saveAllNew(moreEntities));
    ImmutableList<TestEntity> updated =
        ImmutableList.of(
            new TestEntity("entity1", "foo_updated"),
            new TestEntity("entity2", "bar_updated"),
            new TestEntity("entity3", "qux_updated"),
            theEntity);
    assertThrows(
        IllegalArgumentException.class, () -> jpaTm().transact(() -> jpaTm().updateAll(updated)));
    assertThat(jpaTm().transact(() -> jpaTm().loadAll(TestEntity.class)))
        .containsExactlyElementsIn(moreEntities);
  }

  @Test
  void load_succeeds() {
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(theEntity))).isFalse();
    jpaTm().transact(() -> jpaTm().saveNew(theEntity));
    TestEntity persisted = jpaTm().transact(() -> jpaTm().load(theEntityKey));
    assertThat(persisted.name).isEqualTo("theEntity");
    assertThat(persisted.data).isEqualTo("foo");
  }

  @Test
  void load_throwsOnMissingElement() {
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(theEntity))).isFalse();
    assertThrows(
        NoSuchElementException.class, () -> jpaTm().transact(() -> jpaTm().load(theEntityKey)));
  }

  @Test
  void maybeLoad_succeeds() {
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(theEntity))).isFalse();
    jpaTm().transact(() -> jpaTm().saveNew(theEntity));
    TestEntity persisted = jpaTm().transact(() -> jpaTm().maybeLoad(theEntityKey).get());
    assertThat(persisted.name).isEqualTo("theEntity");
    assertThat(persisted.data).isEqualTo("foo");
  }

  @Test
  void maybeLoad_nonExistentObject() {
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(theEntity))).isFalse();
    assertThat(jpaTm().transact(() -> jpaTm().maybeLoad(theEntityKey)).isPresent()).isFalse();
  }

  @Test
  void loadCompoundIdEntity_succeeds() {
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(compoundIdEntity))).isFalse();
    jpaTm().transact(() -> jpaTm().saveNew(compoundIdEntity));
    TestCompoundIdEntity persisted = jpaTm().transact(() -> jpaTm().load(compoundIdEntityKey));
    assertThat(persisted.name).isEqualTo("compoundIdEntity");
    assertThat(persisted.age).isEqualTo(10);
    assertThat(persisted.data).isEqualTo("foo");
  }

  @Test
  void loadAll_succeeds() {
    jpaTm().transact(() -> jpaTm().saveAllNew(moreEntities));
    ImmutableList<TestEntity> persisted = jpaTm().transact(() -> jpaTm().loadAll(TestEntity.class));
    assertThat(persisted).containsExactlyElementsIn(moreEntities);
  }

  @Test
  void delete_succeeds() {
    jpaTm().transact(() -> jpaTm().saveNew(theEntity));
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(theEntity))).isTrue();
    jpaTm().transact(() -> jpaTm().delete(theEntityKey));
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(theEntity))).isFalse();
  }

  @Test
  void delete_returnsZeroWhenNoEntity() {
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(theEntity))).isFalse();
    jpaTm().transact(() -> jpaTm().delete(theEntityKey));
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(theEntity))).isFalse();
  }

  @Test
  void deleteCompoundIdEntity_succeeds() {
    jpaTm().transact(() -> jpaTm().saveNew(compoundIdEntity));
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(compoundIdEntity))).isTrue();
    jpaTm().transact(() -> jpaTm().delete(compoundIdEntityKey));
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(compoundIdEntity))).isFalse();
  }

  @Test
  void assertDelete_throwsExceptionWhenEntityNotDeleted() {
    assertThat(jpaTm().transact(() -> jpaTm().checkExists(theEntity))).isFalse();
    assertThrows(
        IllegalArgumentException.class,
        () -> jpaTm().transact(() -> jpaTm().assertDelete(theEntityKey)));
  }

  private void insertPerson(int age) {
    jpaTm()
        .getEntityManager()
        .createNativeQuery(String.format("INSERT INTO Person (age) VALUES (%d)", age))
        .executeUpdate();
  }

  private void insertCompany(String name) {
    jpaTm()
        .getEntityManager()
        .createNativeQuery(String.format("INSERT INTO Company (name) VALUES ('%s')", name))
        .executeUpdate();
  }

  private void assertPersonExist(int age) {
    jpaTm()
        .transact(
            () -> {
              EntityManager em = jpaTm().getEntityManager();
              Integer maybeAge =
                  (Integer)
                      em.createNativeQuery(
                              String.format("SELECT age FROM Person WHERE age = %d", age))
                          .getSingleResult();
              assertThat(maybeAge).isEqualTo(age);
            });
  }

  private void assertCompanyExist(String name) {
    jpaTm()
        .transact(
            () -> {
              String maybeName =
                  (String)
                      jpaTm()
                          .getEntityManager()
                          .createNativeQuery(
                              String.format("SELECT name FROM Company WHERE name = '%s'", name))
                          .getSingleResult();
              assertThat(maybeName).isEqualTo(name);
            });
  }

  private void assertPersonCount(int count) {
    assertThat(countTable("Person")).isEqualTo(count);
  }

  private void assertCompanyCount(int count) {
    assertThat(countTable("Company")).isEqualTo(count);
  }

  private void assertPersonEmpty() {
    assertPersonCount(0);
  }

  private void assertCompanyEmpty() {
    assertCompanyCount(0);
  }

  private int countTable(String tableName) {
    return jpaTm()
        .transact(
            () -> {
              BigInteger colCount =
                  (BigInteger)
                      jpaTm()
                          .getEntityManager()
                          .createNativeQuery(String.format("SELECT COUNT(*) FROM %s", tableName))
                          .getSingleResult();
              return colCount.intValue();
            });
  }

  @Entity(name = "TestEntity")
  private static class TestEntity extends ImmutableObject {
    @Id private String name;

    private String data;

    private TestEntity() {}

    private TestEntity(String name, String data) {
      this.name = name;
      this.data = data;
    }
  }

  @Entity(name = "TestCompoundIdEntity")
  @IdClass(CompoundId.class)
  private static class TestCompoundIdEntity extends ImmutableObject {
    @Id private String name;
    @Id private int age;

    private String data;

    private TestCompoundIdEntity() {}

    private TestCompoundIdEntity(String name, int age, String data) {
      this.name = name;
      this.age = age;
      this.data = data;
    }
  }

  private static class CompoundId implements Serializable {
    String name;
    int age;

    private CompoundId() {}

    private CompoundId(String name, int age) {
      this.name = name;
      this.age = age;
    }
  }
}
