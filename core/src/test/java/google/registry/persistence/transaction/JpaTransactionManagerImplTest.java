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
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestRule;
import google.registry.testing.FakeClock;
import java.io.Serializable;
import java.math.BigInteger;
import javax.persistence.Entity;
import javax.persistence.EntityManager;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.PersistenceException;
import javax.persistence.RollbackException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link JpaTransactionManagerImpl}. */
@RunWith(JUnit4.class)
public class JpaTransactionManagerImplTest {

  private final FakeClock fakeClock = new FakeClock();
  private final TestEntity theEntity = new TestEntity("theEntity", "foo");
  private final VKey<TestEntity> theEntityKey = VKey.create(TestEntity.class, "theEntity");
  private final TestCompoundIdEntity compoundIdEntity =
      new TestCompoundIdEntity("compoundIdEntity", 10, "foo");
  private final VKey<TestCompoundIdEntity> compoundIdEntityKey =
      VKey.create(TestCompoundIdEntity.class, new CompoundId("compoundIdEntity", 10));
  private final ImmutableList<TestEntity> moreEntities =
      ImmutableList.of(
          new TestEntity("entity1", "foo"),
          new TestEntity("entity2", "bar"),
          new TestEntity("entity3", "qux"));

  @Rule
  public final JpaUnitTestRule jpaRule =
      new JpaTestRules.Builder()
          .withInitScript(fileClassPath(getClass(), "test_schema.sql"))
          .withClock(fakeClock)
          .withEntityClass(TestEntity.class, TestCompoundIdEntity.class)
          .buildUnitTestRule();

  @Test
  public void inTransaction_returnsCorrespondingResult() {
    assertThat(jpaTm().inTransaction()).isFalse();
    jpaTm().transact(() -> assertThat(jpaTm().inTransaction()).isTrue());
    assertThat(jpaTm().inTransaction()).isFalse();
  }

  @Test
  public void assertInTransaction_throwsExceptionWhenNotInTransaction() {
    assertThrows(PersistenceException.class, () -> jpaTm().assertInTransaction());
    jpaTm().transact(() -> jpaTm().assertInTransaction());
    assertThrows(PersistenceException.class, () -> jpaTm().assertInTransaction());
  }

  @Test
  public void getTransactionTime_throwsExceptionWhenNotInTransaction() {
    FakeClock txnClock = fakeClock;
    txnClock.advanceOneMilli();
    assertThrows(PersistenceException.class, () -> jpaTm().getTransactionTime());
    jpaTm().transact(() -> assertThat(jpaTm().getTransactionTime()).isEqualTo(txnClock.nowUtc()));
    assertThrows(PersistenceException.class, () -> jpaTm().getTransactionTime());
  }

  @Test
  public void transact_succeeds() {
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
  public void transact_hasNoEffectWithPartialSuccess() {
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
  public void transact_reusesExistingTransaction() {
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
  public void create_succeeds() {
    assertThat(jpaTm().checkExists(theEntity)).isFalse();
    jpaTm().create(theEntity);
    assertThat(jpaTm().checkExists(theEntity)).isTrue();
    assertThat(jpaTm().load(theEntityKey).get()).isEqualTo(theEntity);
  }

  @Test
  public void create_throwsExceptionIfEntityExists() {
    assertThat(jpaTm().checkExists(theEntity)).isFalse();
    jpaTm().create(theEntity);
    assertThat(jpaTm().checkExists(theEntity)).isTrue();
    assertThat(jpaTm().load(theEntityKey).get()).isEqualTo(theEntity);
    assertThrows(RollbackException.class, () -> jpaTm().create(theEntity));
  }

  @Test
  public void createCompoundIdEntity_succeeds() {
    assertThat(jpaTm().checkExists(compoundIdEntity)).isFalse();
    jpaTm().create(compoundIdEntity);
    assertThat(jpaTm().checkExists(compoundIdEntity)).isTrue();
    assertThat(jpaTm().load(compoundIdEntityKey).get()).isEqualTo(compoundIdEntity);
  }

  @Test
  public void createAll_succeeds() {
    moreEntities.forEach(entity -> assertThat(jpaTm().checkExists(entity)).isFalse());
    jpaTm().createAll(moreEntities);
    moreEntities.forEach(entity -> assertThat(jpaTm().checkExists(entity)).isTrue());
    assertThat(jpaTm().loadAll(TestEntity.class)).containsExactlyElementsIn(moreEntities);
  }

  @Test
  public void createAll_rollsBackWhenFailure() {
    moreEntities.forEach(entity -> assertThat(jpaTm().checkExists(entity)).isFalse());
    jpaTm().create(moreEntities.get(0));
    assertThrows(RollbackException.class, () -> jpaTm().createAll(moreEntities));
    assertThat(jpaTm().checkExists(moreEntities.get(0))).isTrue();
    assertThat(jpaTm().checkExists(moreEntities.get(1))).isFalse();
    assertThat(jpaTm().checkExists(moreEntities.get(2))).isFalse();
  }

  @Test
  public void createOrUpdate_persistsNewEntity() {
    assertThat(jpaTm().checkExists(theEntity)).isFalse();
    jpaTm().createOrUpdate(theEntity);
    assertThat(jpaTm().checkExists(theEntity)).isTrue();
    assertThat(jpaTm().load(theEntityKey).get()).isEqualTo(theEntity);
  }

  @Test
  public void createOrUpdate_updatesExistingEntity() {
    jpaTm().create(theEntity);
    TestEntity persisted = jpaTm().load(theEntityKey).get();
    assertThat(persisted.data).isEqualTo("foo");
    theEntity.data = "bar";
    jpaTm().createOrUpdate(theEntity);
    persisted = jpaTm().load(theEntityKey).get();
    assertThat(persisted.data).isEqualTo("bar");
  }

  @Test
  public void createOrUpdateAll_succeeds() {
    moreEntities.forEach(entity -> assertThat(jpaTm().checkExists(entity)).isFalse());
    jpaTm().createOrUpdateAll(moreEntities);
    moreEntities.forEach(entity -> assertThat(jpaTm().checkExists(entity)).isTrue());
    assertThat(jpaTm().loadAll(TestEntity.class)).containsExactlyElementsIn(moreEntities);
  }

  @Test
  public void update_succeeds() {
    jpaTm().create(theEntity);
    TestEntity persisted = jpaTm().load(VKey.create(TestEntity.class, "theEntity")).get();
    assertThat(persisted.data).isEqualTo("foo");
    theEntity.data = "bar";
    jpaTm().update(theEntity);
    persisted = jpaTm().load(theEntityKey).get();
    assertThat(persisted.data).isEqualTo("bar");
  }

  @Test
  public void updateCompoundIdEntity_succeeds() {
    jpaTm().create(compoundIdEntity);
    TestCompoundIdEntity persisted = jpaTm().load(compoundIdEntityKey).get();
    assertThat(persisted.data).isEqualTo("foo");
    compoundIdEntity.data = "bar";
    jpaTm().update(compoundIdEntity);
    persisted = jpaTm().load(compoundIdEntityKey).get();
    assertThat(persisted.data).isEqualTo("bar");
  }

  @Test
  public void update_throwsExceptionWhenEntityDoesNotExist() {
    assertThat(jpaTm().checkExists(theEntity)).isFalse();
    assertThrows(IllegalArgumentException.class, () -> jpaTm().update(theEntity));
    assertThat(jpaTm().checkExists(theEntity)).isFalse();
  }

  @Test
  public void updateAll_succeeds() {
    jpaTm().createAll(moreEntities);
    ImmutableList<TestEntity> updated =
        ImmutableList.of(
            new TestEntity("entity1", "foo_updated"),
            new TestEntity("entity2", "bar_updated"),
            new TestEntity("entity3", "qux_updated"));
    jpaTm().updateAll(updated);
    assertThat(jpaTm().loadAll(TestEntity.class)).containsExactlyElementsIn(updated);
  }

  @Test
  public void updateAll_rollsBackWhenFailure() {
    jpaTm().createAll(moreEntities);
    ImmutableList<TestEntity> updated =
        ImmutableList.of(
            new TestEntity("entity1", "foo_updated"),
            new TestEntity("entity2", "bar_updated"),
            new TestEntity("entity3", "qux_updated"),
            theEntity);
    assertThrows(IllegalArgumentException.class, () -> jpaTm().updateAll(updated));
    assertThat(jpaTm().loadAll(TestEntity.class)).containsExactlyElementsIn(moreEntities);
  }

  @Test
  public void load_succeeds() {
    assertThat(jpaTm().checkExists(theEntity)).isFalse();
    jpaTm().create(theEntity);
    TestEntity persisted = jpaTm().load(theEntityKey).get();
    assertThat(persisted.name).isEqualTo("theEntity");
    assertThat(persisted.data).isEqualTo("foo");
  }

  @Test
  public void loadCompoundIdEntity_succeeds() {
    assertThat(jpaTm().checkExists(compoundIdEntity)).isFalse();
    jpaTm().create(compoundIdEntity);
    TestCompoundIdEntity persisted = jpaTm().load(compoundIdEntityKey).get();
    assertThat(persisted.name).isEqualTo("compoundIdEntity");
    assertThat(persisted.age).isEqualTo(10);
    assertThat(persisted.data).isEqualTo("foo");
  }

  @Test
  public void loadAll_succeeds() {
    jpaTm().createAll(moreEntities);
    ImmutableList<TestEntity> persisted = jpaTm().loadAll(TestEntity.class);
    assertThat(persisted).containsExactlyElementsIn(moreEntities);
  }

  @Test
  public void delete_succeeds() {
    jpaTm().create(theEntity);
    assertThat(jpaTm().checkExists(theEntity)).isTrue();
    assertThat(jpaTm().delete(theEntityKey)).isEqualTo(1);
    assertThat(jpaTm().checkExists(theEntity)).isFalse();
  }

  @Test
  public void delete_returnsZeroWhenNoEntity() {
    assertThat(jpaTm().checkExists(theEntity)).isFalse();
    assertThat(jpaTm().delete(theEntityKey)).isEqualTo(0);
    assertThat(jpaTm().checkExists(theEntity)).isFalse();
  }

  @Test
  public void deleteCompoundIdEntity_succeeds() {
    jpaTm().create(compoundIdEntity);
    assertThat(jpaTm().checkExists(compoundIdEntity)).isTrue();
    jpaTm().delete(compoundIdEntityKey);
    assertThat(jpaTm().checkExists(compoundIdEntity)).isFalse();
  }

  @Test
  public void assertDelete_throwsExceptionWhenEntityNotDeleted() {
    assertThat(jpaTm().checkExists(theEntity)).isFalse();
    assertThrows(IllegalArgumentException.class, () -> jpaTm().assertDelete(theEntityKey));
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
