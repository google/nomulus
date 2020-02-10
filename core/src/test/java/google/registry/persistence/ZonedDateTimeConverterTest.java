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

package google.registry.persistence;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import google.registry.model.ImmutableObject;
import google.registry.persistence.converter.ZonedDateTimeConverter;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestRule;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZonedDateTime;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ZonedDateTimeConverter}. */
@RunWith(JUnit4.class)
public class ZonedDateTimeConverterTest {

  @Rule
  public final JpaUnitTestRule jpaRule =
      new JpaTestRules.Builder().withEntityClass(TestEntity.class).buildUnitTestRule();

  private final ZonedDateTimeConverter converter = new ZonedDateTimeConverter();

  @Test
  public void convertToDatabaseColumn_returnsNullIfInputIsNull() {
    assertThat(converter.convertToDatabaseColumn(null)).isNull();
  }

  @Test
  public void convertToDatabaseColumn_convertsCorrectly() {
    ZonedDateTime zonedDateTime = ZonedDateTime.parse("2019-09-01T01:01:01Z");
    assertThat(converter.convertToDatabaseColumn(zonedDateTime).toInstant())
        .isEqualTo(zonedDateTime.toInstant());
  }

  @Test
  public void convertToEntityAttribute_returnsNullIfInputIsNull() {
    assertThat(converter.convertToEntityAttribute(null)).isNull();
  }

  @Test
  public void convertToEntityAttribute_convertsCorrectly() {
    ZonedDateTime zonedDateTime = ZonedDateTime.parse("2019-09-01T01:01:01Z");
    Instant instant = zonedDateTime.toInstant();
    assertThat(converter.convertToEntityAttribute(Timestamp.from(instant)))
        .isEqualTo(zonedDateTime);
  }

  @Test
  public void converter_generatesTimestampWithNormalizedZone() {
    ZonedDateTime zdt = ZonedDateTime.parse("2019-09-01T01:01:01Z");
    TestEntity entity = new TestEntity("normalized_utc_time", zdt);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(entity));
    TestEntity retrievedEntity =
        jpaTm()
            .transact(
                () -> jpaTm().getEntityManager().find(TestEntity.class, "normalized_utc_time"));
    assertThat(retrievedEntity.zdt.toString()).isEqualTo("2019-09-01T01:01:01Z");
  }

  @Test
  public void converter_convertsNonNormalizedZoneCorrectly() {
    ZonedDateTime zdt = ZonedDateTime.parse("2019-09-01T01:01:01Z[UTC]");
    TestEntity entity = new TestEntity("non_normalized_utc_time", zdt);

    jpaTm().transact(() -> jpaTm().getEntityManager().persist(entity));
    TestEntity retrievedEntity =
        jpaTm()
            .transact(
                () -> jpaTm().getEntityManager().find(TestEntity.class, "non_normalized_utc_time"));
    assertThat(retrievedEntity.zdt.toString()).isEqualTo("2019-09-01T01:01:01Z");
  }

  @Test
  public void converter_convertsNonUtcZoneCorrectly() {
    ZonedDateTime zdt = ZonedDateTime.parse("2019-09-01T01:01:01+05:00");
    TestEntity entity = new TestEntity("new_york_time", zdt);

    jpaTm().transact(() -> jpaTm().getEntityManager().persist(entity));
    TestEntity retrievedEntity =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "new_york_time"));
    assertThat(retrievedEntity.zdt.toString()).isEqualTo("2019-08-31T20:01:01Z");
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  private static class TestEntity extends ImmutableObject {

    @Id String name;

    ZonedDateTime zdt;

    public TestEntity() {}

    TestEntity(String name, ZonedDateTime zdt) {
      this.name = name;
      this.zdt = zdt;
    }
  }
}
