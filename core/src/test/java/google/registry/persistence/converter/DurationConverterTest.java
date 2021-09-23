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
import static google.registry.testing.DatabaseHelper.insertInDb;

import google.registry.model.ImmutableObject;
import google.registry.model.replay.EntityTest.EntityForTesting;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.joda.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.postgresql.util.PGInterval;

/** Unit tests for {@link DurationConverter}. */
public class DurationConverterTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestExtensions.Builder().withEntityClass(DurationTestEntity.class).buildUnitTestExtension();

  private final DurationConverter converter = new DurationConverter();

  @Test
  void testNulls() {
    assertThat(converter.convertToDatabaseColumn(null)).isEqualTo(new PGInterval());
    assertThat(converter.convertToEntityAttribute(new PGInterval())).isNull();
  }

  @Test
  void testRoundTrip() {
    Duration testDuration =
        Duration.standardDays(6)
            .plus(Duration.standardHours(10))
            .plus(Duration.standardMinutes(30))
            .plus(Duration.standardSeconds(15))
            .plus(Duration.millis(7));
    assertPersistedEntityHasSameDuration(testDuration);
  }

  @Test
  void testRoundTripLargeNumberOfDays() {
    Duration testDuration =
        Duration.standardDays(10001).plus(Duration.standardHours(100)).plus(Duration.millis(790));
    assertPersistedEntityHasSameDuration(testDuration);
  }

  @Test
  void testRoundTripLessThanOneDay() {
    Duration testDuration =
        Duration.standardHours(15)
            .plus(Duration.standardMinutes(40))
            .plus(Duration.standardSeconds(50));
    assertPersistedEntityHasSameDuration(testDuration);
  }

  @Test
  void testRoundTripExactOneDay() {
    Duration testDuration = Duration.standardDays(1);
    assertPersistedEntityHasSameDuration(testDuration);
  }

  private void assertPersistedEntityHasSameDuration(Duration duration) {
    DurationTestEntity entity = new DurationTestEntity(duration);
    insertInDb(entity);
    DurationTestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(DurationTestEntity.class, "id"));
    assertThat(persisted.duration.getMillis()).isEqualTo(duration.getMillis());
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  @EntityForTesting
  public static class DurationTestEntity extends ImmutableObject {

    @Id String name = "id";

    Duration duration;

    public DurationTestEntity() {}

    DurationTestEntity(Duration duration) {
      this.duration = duration;
    }
  }
}
