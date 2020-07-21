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

package google.registry.persistence.converter;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestExtension;
import java.sql.Timestamp;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link DateTimeConverter}. */
public class DateTimeConverterTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestRules.Builder().withEntityClass(TestEntity.class).buildUnitTestRule();

  private final DateTimeConverter converter = new DateTimeConverter();

  @Test
  void convertToDatabaseColumn_returnsNullIfInputIsNull() {
    assertThat(converter.convertToDatabaseColumn(null)).isNull();
  }

  @Test
  void convertToDatabaseColumn_convertsCorrectly() {
    DateTime dateTime = DateTime.parse("2019-09-01T01:01:01");
    assertThat(converter.convertToDatabaseColumn(dateTime).getTime())
        .isEqualTo(dateTime.getMillis());
  }

  @Test
  void convertToEntityAttribute_returnsNullIfInputIsNull() {
    assertThat(converter.convertToEntityAttribute(null)).isNull();
  }

  @Test
  void convertToEntityAttribute_convertsCorrectly() {
    DateTime dateTime = DateTime.parse("2019-09-01T01:01:01Z");
    long millis = dateTime.getMillis();
    assertThat(converter.convertToEntityAttribute(new Timestamp(millis))).isEqualTo(dateTime);
  }

  static DateTime parseDateTime(String value) {
    return ISODateTimeFormat.dateTimeNoMillis().withOffsetParsed().parseDateTime(value);
  }

  @Test
  void converter_generatesTimestampWithNormalizedZone() {
    DateTime dt = parseDateTime("2019-09-01T01:01:01Z");
    TestEntity entity = new TestEntity("normalized_utc_time", dt);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(entity));
    TestEntity retrievedEntity =
        jpaTm()
            .transact(
                () -> jpaTm().getEntityManager().find(TestEntity.class, "normalized_utc_time"));
    assertThat(retrievedEntity.dt.toString()).isEqualTo("2019-09-01T01:01:01.000Z");
  }

  @Test
  void converter_convertsNonUtcZoneCorrectly() {
    DateTime dt = parseDateTime("2019-09-01T01:01:01-05:00");
    TestEntity entity = new TestEntity("new_york_time", dt);

    jpaTm().transact(() -> jpaTm().getEntityManager().persist(entity));
    TestEntity retrievedEntity =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "new_york_time"));
    assertThat(retrievedEntity.dt.toString()).isEqualTo("2019-09-01T06:01:01.000Z");
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  private static class TestEntity extends ImmutableObject {

    @Id String name;

    DateTime dt;

    public TestEntity() {}

    TestEntity(String name, DateTime dt) {
      this.name = name;
      this.dt = dt;
    }
  }
}
