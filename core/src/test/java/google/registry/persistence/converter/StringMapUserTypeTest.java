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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NoResultException;
import java.util.Map;
import org.hibernate.annotations.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link StringMapUserType}. */
public class StringMapUserTypeTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestExtensions.Builder().withEntityClass(TestEntity.class).buildUnitTestExtension();

  private static final ImmutableMap<String, String> MAP =
      ImmutableMap.of(
          "key1", "value1",
          "key2", "value2",
          "key3", "value3");

  @Test
  void roundTripConversion_returnsSameMap() {
    TestEntity testEntity = new TestEntity(MAP);
    persistResource(testEntity);
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.map).containsExactlyEntriesIn(MAP);
  }

  @Test
  void testUpdateColumn_succeeds() {
    TestEntity testEntity = new TestEntity(MAP);
    persistResource(testEntity);
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.map).containsExactlyEntriesIn(MAP);
    persisted.map = ImmutableMap.of("key4", "value4");
    tm().transact(() -> tm().getEntityManager().merge(persisted));
    TestEntity updated = tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(updated.map).containsExactly("key4", "value4");
  }

  @Test
  void testNullValue_writesAndReadsNullSuccessfully() {
    TestEntity testEntity = new TestEntity(null);
    persistResource(testEntity);
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.map).isNull();
  }

  @Test
  void testEmptyMap_writesAndReadsEmptyCollectionSuccessfully() {
    TestEntity testEntity = new TestEntity(ImmutableMap.of());
    persistResource(testEntity);
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.map).isEmpty();
  }

  @Test
  void testNativeQuery_succeeds() {
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
    return tm().transact(() -> tm().getEntityManager().createNativeQuery(sql).getSingleResult());
  }

  private static Object executeNativeQuery(String sql) {
    return tm().transact(() -> tm().getEntityManager().createNativeQuery(sql).executeUpdate());
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  private static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    @Type(StringMapUserType.class)
    Map<String, String> map;

    private TestEntity() {}

    private TestEntity(Map<String, String> map) {
      this.map = map;
    }
  }
}
