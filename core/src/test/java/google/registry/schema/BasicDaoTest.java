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

package google.registry.schema;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestRule;
import java.io.Serializable;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.RollbackException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link BasicDao}. */
@RunWith(JUnit4.class)
public class BasicDaoTest {

  private final TestEntity theEntity = new TestEntity("theEntity", "foo");
  private final TestCompoundIdEntity compoundIdEntity =
      new TestCompoundIdEntity("compoundIdEntity", 10, "foo");
  private final CompoundId compoundId = new CompoundId("compoundIdEntity", 10);
  private final ImmutableList<TestEntity> moreEntities =
      ImmutableList.of(
          new TestEntity("entity1", "foo"),
          new TestEntity("entity2", "bar"),
          new TestEntity("entity3", "qux"));

  private TestEntityDao dao;
  private TestCompoundIdEntityDao compoundIdDao;

  @Rule
  public final JpaUnitTestRule jpaRule =
      new JpaTestRules.Builder()
          .withEntityClass(TestEntity.class, TestCompoundIdEntity.class)
          .buildUnitTestRule();

  @Before
  public void setUp() {
    dao = new TestEntityDao();
    compoundIdDao = new TestCompoundIdEntityDao();
  }

  @Test
  public void saveNew_succeeds() {
    assertThat(dao.checkExists(theEntity)).isFalse();
    dao.saveNew(theEntity);
    assertThat(dao.checkExists(theEntity)).isTrue();
  }

  @Test
  public void saveNew_throwsExceptionIfEntityExists() {
    assertThat(dao.checkExists(theEntity)).isFalse();
    dao.saveNew(theEntity);
    assertThat(dao.checkExists(theEntity)).isTrue();
    assertThrows(RollbackException.class, () -> dao.saveNew(theEntity));
  }

  @Test
  public void saveNewCompoundIdEntity_succeeds() {
    assertThat(compoundIdDao.checkExists(compoundIdEntity)).isFalse();
    compoundIdDao.saveNew(compoundIdEntity);
    assertThat(compoundIdDao.checkExists(compoundIdEntity)).isTrue();
  }

  @Test
  public void saveAllNew_succeeds() {
    moreEntities.forEach(entity -> assertThat(dao.checkExists(entity)).isFalse());
    dao.saveAllNew(moreEntities);
    moreEntities.forEach(entity -> assertThat(dao.checkExists(entity)).isTrue());
  }

  @Test
  public void saveAllNew_rollsBackWhenFailure() {
    moreEntities.forEach(entity -> assertThat(dao.checkExists(entity)).isFalse());
    dao.saveNew(moreEntities.get(0));
    assertThrows(RollbackException.class, () -> dao.saveAllNew(moreEntities));
    assertThat(dao.checkExists(moreEntities.get(0))).isTrue();
    assertThat(dao.checkExists(moreEntities.get(1))).isFalse();
    assertThat(dao.checkExists(moreEntities.get(2))).isFalse();
  }

  @Test
  public void merge_persistsNewEntity() {
    assertThat(dao.checkExists(theEntity)).isFalse();
    dao.merge(theEntity);
    assertThat(dao.checkExists(theEntity)).isTrue();
  }

  @Test
  public void merge_updatesExistingEntity() {
    dao.saveNew(theEntity);
    TestEntity persisted = dao.load("theEntity").get();
    assertThat(persisted.data).isEqualTo("foo");
    theEntity.data = "bar";
    dao.merge(theEntity);
    persisted = dao.load("theEntity").get();
    assertThat(persisted.data).isEqualTo("bar");
  }

  @Test
  public void mergeAll_succeeds() {
    moreEntities.forEach(entity -> assertThat(dao.checkExists(entity)).isFalse());
    dao.mergeAll(moreEntities);
    moreEntities.forEach(entity -> assertThat(dao.checkExists(entity)).isTrue());
  }

  @Test
  public void update_succeeds() {
    dao.saveNew(theEntity);
    TestEntity persisted = dao.load("theEntity").get();
    assertThat(persisted.data).isEqualTo("foo");
    theEntity.data = "bar";
    dao.update(theEntity);
    persisted = dao.load("theEntity").get();
    assertThat(persisted.data).isEqualTo("bar");
  }

  @Test
  public void updateCompoundIdEntity_succeeds() {
    compoundIdDao.saveNew(compoundIdEntity);
    TestCompoundIdEntity persisted = compoundIdDao.load(compoundId).get();
    assertThat(persisted.data).isEqualTo("foo");
    compoundIdEntity.data = "bar";
    compoundIdDao.update(compoundIdEntity);
    persisted = compoundIdDao.load(compoundId).get();
    assertThat(persisted.data).isEqualTo("bar");
  }

  @Test
  public void update_throwsExceptionWhenEntityDoesNotExist() {
    assertThat(dao.checkExists(theEntity)).isFalse();
    assertThrows(IllegalArgumentException.class, () -> dao.update(theEntity));
    assertThat(dao.checkExists(theEntity)).isFalse();
  }

  @Test
  public void updateAll_succeeds() {
    dao.saveAllNew(moreEntities);
    ImmutableList<TestEntity> updated =
        ImmutableList.of(
            new TestEntity("entity1", "foo_updated"),
            new TestEntity("entity2", "bar_updated"),
            new TestEntity("entity3", "qux_updated"));
    dao.updateAll(updated);
    assertThat(dao.loadAll()).containsExactlyElementsIn(updated);
  }

  @Test
  public void updateAll_rollsBackWhenFailure() {
    dao.saveAllNew(moreEntities);
    ImmutableList<TestEntity> updated =
        ImmutableList.of(
            new TestEntity("entity1", "foo_updated"),
            new TestEntity("entity2", "bar_updated"),
            new TestEntity("entity3", "qux_updated"),
            theEntity);
    assertThrows(IllegalArgumentException.class, () -> dao.updateAll(updated));
    assertThat(dao.loadAll()).containsExactlyElementsIn(moreEntities);
  }

  @Test
  public void load_succeeds() {
    assertThat(dao.checkExists(theEntity)).isFalse();
    dao.saveNew(theEntity);
    TestEntity persisted = dao.load("theEntity").get();
    assertThat(persisted.name).isEqualTo("theEntity");
    assertThat(persisted.data).isEqualTo("foo");
  }

  @Test
  public void loadCompoundIdEntity_succeeds() {
    assertThat(compoundIdDao.checkExists(compoundIdEntity)).isFalse();
    compoundIdDao.saveNew(compoundIdEntity);
    TestCompoundIdEntity persisted = compoundIdDao.load(compoundId).get();
    assertThat(persisted.name).isEqualTo("compoundIdEntity");
    assertThat(persisted.age).isEqualTo(10);
    assertThat(persisted.data).isEqualTo("foo");
  }

  @Test
  public void loadAll_succeeds() {
    dao.saveAllNew(moreEntities);
    ImmutableList<TestEntity> persisted = dao.loadAll();
    assertThat(persisted).containsExactlyElementsIn(moreEntities);
  }

  @Test
  public void delete_succeeds() {
    dao.saveNew(theEntity);
    assertThat(dao.checkExists(theEntity)).isTrue();
    assertThat(dao.delete("theEntity")).isEqualTo(1);
    assertThat(dao.checkExists(theEntity)).isFalse();
  }

  @Test
  public void delete_returnsZeroWhenNoEntity() {
    assertThat(dao.checkExists(theEntity)).isFalse();
    assertThat(dao.delete("theEntity")).isEqualTo(0);
    assertThat(dao.checkExists(theEntity)).isFalse();
  }

  @Test
  public void deleteCompoundIdEntity_succeeds() {
    compoundIdDao.saveNew(compoundIdEntity);
    assertThat(compoundIdDao.checkExists(compoundIdEntity)).isTrue();
    compoundIdDao.delete(compoundId);
    assertThat(compoundIdDao.checkExists(compoundIdEntity)).isFalse();
  }

  @Test
  public void assertDelete_throwsExceptionWhenEntityNotDeleted() {
    assertThat(dao.checkExists(theEntity)).isFalse();
    assertThrows(IllegalArgumentException.class, () -> dao.assertDelete("theEntity"));
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
  private static class TestCompoundIdEntity {
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

  private static class TestEntityDao extends BasicDao<TestEntity> {}

  private static class TestCompoundIdEntityDao extends BasicDao<TestCompoundIdEntity> {}
}
