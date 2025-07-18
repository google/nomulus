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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NoResultException;
import java.util.List;
import java.util.stream.Stream;
import org.hibernate.annotations.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link StringCollectionUserType}. */
public class StringCollectionUserTypeTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestExtensions.Builder().withEntityClass(TestEntity.class).buildUnitTestExtension();

  @Test
  void roundTripConversion_returnsSameStringList() {
    List<ListElement> value =
        ImmutableList.of(new ListElement("app"), new ListElement("dev"), new ListElement("com"));
    TestEntity testEntity = new TestEntity(value);
    persistResource(testEntity);
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.value).containsExactlyElementsIn(value);
  }

  @Test
  void testMerge_succeeds() {
    List<ListElement> value =
        ImmutableList.of(new ListElement("app"), new ListElement("dev"), new ListElement("com"));
    TestEntity testEntity = new TestEntity(value);
    persistResource(testEntity);
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    persisted.value = ImmutableList.of(new ListElement("app"), new ListElement("org"));
    tm().transact(() -> tm().getEntityManager().merge(persisted));
    TestEntity updated = tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(updated.value).containsExactly(new ListElement("app"), new ListElement("org"));
  }

  @Test
  void testNullValue_writesAndReadsNullSuccessfully() {
    TestEntity testEntity = new TestEntity(null);
    persistResource(testEntity);
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.value).isNull();
  }

  @Test
  void testEmptyCollection_writesAndReadsEmptyCollectionSuccessfully() {
    TestEntity testEntity = new TestEntity(ImmutableList.of());
    persistResource(testEntity);
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.value).isEmpty();
  }

  @Test
  void testNativeQuery_succeeds() throws Exception {
    executeNativeQuery("INSERT INTO \"TestEntity\" (name, value) VALUES ('id', '{app, dev}')");

    assertThat(
            getSingleResultFromNativeQuery("SELECT value[1] FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo("app");
    assertThat(
            getSingleResultFromNativeQuery("SELECT value[2] FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo("dev");

    executeNativeQuery("UPDATE \"TestEntity\" SET value = '{com, gov}' WHERE name = 'id'");

    assertThat(
            getSingleResultFromNativeQuery("SELECT value[1] FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo("com");
    assertThat(
            getSingleResultFromNativeQuery("SELECT value[2] FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo("gov");

    executeNativeQuery("DELETE FROM \"TestEntity\" WHERE name = 'id'");
    assertThrows(
        NoResultException.class,
        () ->
            getSingleResultFromNativeQuery(
                "SELECT value[1] FROM \"TestEntity\" WHERE name = 'id'"));
  }

  private static Object getSingleResultFromNativeQuery(String sql) {
    return tm().transact(() -> tm().getEntityManager().createNativeQuery(sql).getSingleResult());
  }

  private static Object executeNativeQuery(String sql) {
    return tm().transact(() -> tm().getEntityManager().createNativeQuery(sql).executeUpdate());
  }

  private static record ListElement(String value) {}

  private static class TestListUserType
      extends StringCollectionUserType<ListElement, List<ListElement>> {

    @Override
    String[] toJdbcObject(List<ListElement> collection) {
      return collection.stream().map(ListElement::value).toList().toArray(new String[0]);
    }

    @Override
    List<ListElement> toEntity(String[] data) {
      return Stream.of(data).map(ListElement::new).collect(toImmutableList());
    }

    @Override
    public Class<List<ListElement>> returnedClass() {
      return (Class<List<ListElement>>) ((Object) List.class);
    }
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  private static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    @Type(TestListUserType.class)
    List<ListElement> value;

    private TestEntity() {}

    private TestEntity(List<ListElement> value) {
      this.value = value;
    }
  }
}
