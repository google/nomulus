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
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import google.registry.model.ImmutableObject;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.model.common.TimedTransitionProperty.TimedTransition;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestExtension;
import java.util.Map;
import javax.persistence.Converter;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.NoResultException;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link TimedTransitionPropertyConverterBase}. */
class TimedTransitionPropertyConverterBaseTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpa =
      new JpaTestRules.Builder()
          .withInitScript("sql/flyway/V14__load_extension_for_hstore.sql")
          .withEntityClass(TestTimedTransitionPropertyConverter.class, TestEntity.class)
          .buildUnitTestRule();

  private static final DateTime DATE_1 = DateTime.parse("2001-01-01T00:00:00.000Z");
  private static final DateTime DATE_2 = DateTime.parse("2002-01-01T00:00:00.000Z");

  private static final ImmutableSortedMap<DateTime, String> VALUES =
      ImmutableSortedMap.of(
          START_OF_TIME, "val1",
          DATE_1, "val2",
          DATE_2, "val3");

  private static final TimedTransitionProperty<String, TestTransition> TIMED_TRANSITION_PROPERTY =
      TimedTransitionProperty.fromValueMap(VALUES, TestTransition.class);

  @Test
  void roundTripConversion_returnsSameTimedTransitionProperty() {
    TestEntity testEntity = new TestEntity(TIMED_TRANSITION_PROPERTY);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(testEntity));
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.property).containsExactlyEntriesIn(TIMED_TRANSITION_PROPERTY);
  }

  @Test
  void testUpdateColumn_succeeds() {
    TestEntity testEntity = new TestEntity(TIMED_TRANSITION_PROPERTY);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(testEntity));
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.property).containsExactlyEntriesIn(TIMED_TRANSITION_PROPERTY);
    ImmutableSortedMap<DateTime, String> newValues = ImmutableSortedMap.of(START_OF_TIME, "val4");
    persisted.property = TimedTransitionProperty.fromValueMap(newValues, TestTransition.class);
    jpaTm().transact(() -> jpaTm().getEntityManager().merge(persisted));
    TestEntity updated =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(updated.property.toValueMap()).isEqualTo(newValues);
  }

  @Test
  void testNullValue_writesAndReadsNullSuccessfully() {
    TestEntity testEntity = new TestEntity(null);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(testEntity));
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
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
        .isEqualTo(START_OF_TIME.toString());
    assertThat(
            getSingleResultFromNativeQuery(
                "SELECT property -> 'val2' FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo(DATE_1.toString());

    executeNativeQuery(
        "UPDATE \"TestEntity\" SET property = 'val3=>2002-01-01T00:00:00.000Z' WHERE name = 'id'");

    assertThat(
            getSingleResultFromNativeQuery(
                "SELECT property -> 'val3' FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo(DATE_2.toString());

    executeNativeQuery("DELETE FROM \"TestEntity\" WHERE name = 'id'");

    assertThrows(
        NoResultException.class,
        () ->
            getSingleResultFromNativeQuery(
                "SELECT property -> 'val3' FROM \"TestEntity\" WHERE name = 'id'"));
  }

  private static Object getSingleResultFromNativeQuery(String sql) {
    return jpaTm()
        .transact(() -> jpaTm().getEntityManager().createNativeQuery(sql).getSingleResult());
  }

  private static void executeNativeQuery(String sql) {
    jpaTm().transact(() -> jpaTm().getEntityManager().createNativeQuery(sql).executeUpdate());
  }

  public static class TestTransition extends TimedTransition<String> {
    private String transition;

    @Override
    public String getValue() {
      return transition;
    }

    @Override
    protected void setValue(String transition) {
      this.transition = transition;
    }
  }

  @Converter(autoApply = true)
  private static class TestTimedTransitionPropertyConverter
      extends TimedTransitionPropertyConverterBase<String, TestTransition> {

    @Override
    Map.Entry<DateTime, String> convertToEntityMapEntry(Map.Entry<String, String> entry) {
      return Maps.immutableEntry(DateTime.parse(entry.getKey()), entry.getValue());
    }

    @Override
    Class<TestTransition> getTimedTransitionSubclass() {
      return TestTransition.class;
    }

    @Override
    Map.Entry<String, String> convertToDatabaseMapEntry(Map.Entry<DateTime, TestTransition> entry) {
      return Maps.immutableEntry(entry.getKey().toString(), entry.getValue().getValue());
    }
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  private static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    TimedTransitionProperty<String, TestTransition> property;

    private TestEntity() {}

    private TestEntity(TimedTransitionProperty<String, TestTransition> timedTransitionProperty) {
      this.property = timedTransitionProperty;
    }
  }
}
