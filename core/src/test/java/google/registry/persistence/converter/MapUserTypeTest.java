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

package google.registry.persistence.converter;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestRule;
import java.util.Map;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.NoResultException;
import org.hibernate.annotations.Type;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link MapUserType}. */
@RunWith(JUnit4.class)
public class MapUserTypeTest {

  // Reusing production script sql/flyway/V14__load_extension_for_hstore.sql, which loads the
  // hstore extension but nothing else.
  @Rule
  public final JpaUnitTestRule jpaRule =
      new JpaTestRules.Builder()
          .withInitScript("sql/flyway/V14__load_extension_for_hstore.sql")
          .withEntityClass(TestEntity.class)
          .buildUnitTestRule();

  @Test
  public void roundTripConversion_returnsSameMap() {
    Map<String, String> map = ImmutableMap.of("key1", "value1", "key2", "value2");
    TestEntity testEntity = new TestEntity(map);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(testEntity));
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.map).containsExactly("key1", "value1", "key2", "value2");
  }

  @Test
  public void testMerge_succeeds() {
    Map<String, String> map = ImmutableMap.of("key1", "value1", "key2", "value2");
    TestEntity testEntity = new TestEntity(map);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(testEntity));
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    persisted.map = ImmutableMap.of("key3", "value3");
    jpaTm().transact(() -> jpaTm().getEntityManager().merge(persisted));
    TestEntity updated =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(updated.map).containsExactly("key3", "value3");
  }

  @Test
  public void testNullValue_writesAndReadsNullSuccessfully() {
    TestEntity testEntity = new TestEntity(null);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(testEntity));
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.map).isNull();
  }

  @Test
  public void testEmptyCollection_writesAndReadsEmptyCollectionSuccessfully() {
    TestEntity testEntity = new TestEntity(ImmutableMap.of());
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(testEntity));
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.map).isEmpty();
  }

  @Test
  public void testNativeQuery_succeeds() throws Exception {
    executeNativeQuery(
        "INSERT INTO \"TestEntity\" (name, map) VALUES ('id', 'key1=>value1, key2=>value2')");

    assertThat(
            getSingleResultFromNativeQuery(
                "SELECT map -> 'key1' FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo("value1");
    assertThat(
            getSingleResultFromNativeQuery(
                "SELECT map -> 'key2' FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo("value2");

    executeNativeQuery("UPDATE \"TestEntity\" SET map = 'key3=>value3' WHERE name = 'id'");

    assertThat(
            getSingleResultFromNativeQuery(
                "SELECT map -> 'key3' FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo("value3");

    executeNativeQuery("DELETE FROM \"TestEntity\" WHERE name = 'id'");
    assertThrows(
        NoResultException.class,
        () ->
            getSingleResultFromNativeQuery(
                "SELECT map -> 'key3' FROM \"TestEntity\" WHERE name = 'id'"));
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

    @Type(type = "google.registry.persistence.converter.MapUserType")
    Map<String, String> map;

    private TestEntity() {}

    private TestEntity(Map<String, String> map) {
      this.map = map;
    }
  }
}
