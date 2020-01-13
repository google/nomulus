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
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.model.transaction.JpaTestRules;
import google.registry.model.transaction.JpaTestRules.JpaUnitTestRule;
import java.util.List;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.NoResultException;
import org.hibernate.annotations.Type;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link StringListUserType}. */
@RunWith(JUnit4.class)
public class StringListUserTypeTest {
  @Rule
  public final JpaUnitTestRule jpaRule =
      new JpaTestRules.Builder().withEntityClass(TestEntity.class).buildUnitTestRule();

  @Test
  public void roundTripConversion_returnsSameStringList() {
    List<String> tlds = ImmutableList.of("app", "dev", "how");
    TestEntity testEntity = new TestEntity(tlds);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(testEntity));
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.tlds).containsExactly("app", "dev", "how");
  }

  @Test
  public void testMerge_succeeds() {
    List<String> tlds = ImmutableList.of("app", "dev", "how");
    TestEntity testEntity = new TestEntity(tlds);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(testEntity));
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    persisted.tlds = ImmutableList.of("com", "gov");
    jpaTm().transact(() -> jpaTm().getEntityManager().merge(persisted));
    TestEntity updated =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(updated.tlds).containsExactly("com", "gov");
  }

  @Test
  public void testNullValue_writesAndReadsNullSuccessfully() {
    TestEntity testEntity = new TestEntity(null);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(testEntity));
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.tlds).isNull();
  }

  @Test
  public void testEmptyCollection_writesAndReadsEmptyCollectionSuccessfully() {
    TestEntity testEntity = new TestEntity(ImmutableList.of());
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(testEntity));
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.tlds).isEmpty();
  }

  @Test
  public void testNativeQuery_succeeds() throws Exception {
    executeNativeQuery("INSERT INTO \"TestEntity\" (name, tlds) VALUES ('id', '{app, dev}')");

    assertThat(
            getSingleResultFromNativeQuery("SELECT tlds[1] FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo("app");
    assertThat(
            getSingleResultFromNativeQuery("SELECT tlds[2] FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo("dev");

    executeNativeQuery("UPDATE \"TestEntity\" SET tlds = '{com, gov}' WHERE name = 'id'");

    assertThat(
            getSingleResultFromNativeQuery("SELECT tlds[1] FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo("com");
    assertThat(
            getSingleResultFromNativeQuery("SELECT tlds[2] FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo("gov");

    executeNativeQuery("DELETE FROM \"TestEntity\" WHERE name = 'id'");
    assertThrows(
        NoResultException.class,
        () ->
            getSingleResultFromNativeQuery("SELECT tlds[1] FROM \"TestEntity\" WHERE name = 'id'"));
  }

  private static Object getSingleResultFromNativeQuery(String sql) {
    return jpaTm()
        .transact(() -> jpaTm().getEntityManager().createNativeQuery(sql).getSingleResult());
  }

  private static Object executeNativeQuery(String sql) {
    return jpaTm()
        .transact(() -> jpaTm().getEntityManager().createNativeQuery(sql).executeUpdate());
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  private static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    @Type(type = "google.registry.persistence.StringListUserType")
    List<String> tlds;

    private TestEntity() {}

    private TestEntity(List<String> tlds) {
      this.tlds = tlds;
    }
  }
}
