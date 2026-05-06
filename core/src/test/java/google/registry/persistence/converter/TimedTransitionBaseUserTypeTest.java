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
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static google.registry.util.DateTimeUtils.formatInstant;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.ImmutableObject;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NoResultException;
import java.time.Instant;
import org.hibernate.annotations.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link TimedTransitionBaseUserType}. */
class TimedTransitionBaseUserTypeTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpa =
      new JpaTestExtensions.Builder().withEntityClass(TestEntity.class).buildUnitTestExtension();

  private static final Instant DATE_1 = Instant.parse("2001-01-01T00:00:00.000Z");
  private static final Instant DATE_2 = Instant.parse("2002-01-01T00:00:00.000Z");

  private static final ImmutableSortedMap<Instant, String> VALUES =
      ImmutableSortedMap.of(
          START_INSTANT, "val1",
          DATE_1, "val2",
          DATE_2, "val3");

  private static final TimedTransitionProperty<String> TIMED_TRANSITION_PROPERTY =
      TimedTransitionProperty.fromValueMap(VALUES);

  @Test
  void roundTripConversion_returnsSameTimedTransitionProperty() {
    TestEntity testEntity = new TestEntity(TIMED_TRANSITION_PROPERTY);
    persistResource(testEntity);
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.property.toValueMap())
        .containsExactlyEntriesIn(TIMED_TRANSITION_PROPERTY.toValueMap());
  }

  @Test
  void testUpdateColumn_succeeds() {
    TestEntity testEntity = new TestEntity(TIMED_TRANSITION_PROPERTY);
    persistResource(testEntity);
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.property.toValueMap())
        .containsExactlyEntriesIn(TIMED_TRANSITION_PROPERTY.toValueMap());
    ImmutableSortedMap<Instant, String> newValues = ImmutableSortedMap.of(START_INSTANT, "val4");
    persisted.property = TimedTransitionProperty.fromValueMap(newValues);
    tm().transact(() -> tm().getEntityManager().merge(persisted));
    TestEntity updated = tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(updated.property.toValueMap()).isEqualTo(newValues);
  }

  @Test
  void testNullValue_writesAndReadsNullSuccessfully() {
    TestEntity testEntity = new TestEntity(null);
    persistResource(testEntity);
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.property).isNull();
  }

  @Test
  void testNativeQuery_succeeds() {
    executeNativeQuery(
        "INSERT INTO \"TestEntity\" (name, property) VALUES ('id',"
            + " 'val1=>1970-01-01T00:00:00.000Z, val2=>2001-01-01T00:00:00.000Z')");

    assertThat(
            getSingleResultFromNativeQuery(
                "SELECT property -> 'val1' FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo(formatInstant(START_INSTANT));
    assertThat(
            getSingleResultFromNativeQuery(
                "SELECT property -> 'val2' FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo(formatInstant(DATE_1));

    executeNativeQuery(
        "UPDATE \"TestEntity\" SET property = 'val3=>2002-01-01T00:00:00.000Z' WHERE name = 'id'");

    assertThat(
            getSingleResultFromNativeQuery(
                "SELECT property -> 'val3' FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo(formatInstant(DATE_2));

    executeNativeQuery("DELETE FROM \"TestEntity\" WHERE name = 'id'");

    assertThrows(
        NoResultException.class,
        () ->
            getSingleResultFromNativeQuery(
                "SELECT property -> 'val3' FROM \"TestEntity\" WHERE name = 'id'"));
  }

  private static Object getSingleResultFromNativeQuery(String sql) {
    return tm().transact(() -> tm().getEntityManager().createNativeQuery(sql).getSingleResult());
  }

  private static void executeNativeQuery(String sql) {
    tm().transact(() -> tm().getEntityManager().createNativeQuery(sql).executeUpdate());
  }

  private static class StringTransitionUserType extends TimedTransitionBaseUserType<String> {

    @Override
    String valueToString(String value) {
      return value;
    }

    @Override
    String stringToValue(String string) {
      return string;
    }
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  private static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    @Type(StringTransitionUserType.class)
    @Column(columnDefinition = "hstore")
    TimedTransitionProperty<String> property;

    private TestEntity() {}

    private TestEntity(TimedTransitionProperty<String> timedTransitionProperty) {
      property = timedTransitionProperty;
    }
  }
}
