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

import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestRule;
import google.registry.schema.replay.EntityTest.EntityForTesting;
import java.math.BigInteger;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.joda.time.Duration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link DurationConverter}. */
@RunWith(JUnit4.class)
public class DurationConverterTest {

  @Rule
  public final JpaUnitTestRule jpaRule =
      new JpaTestRules.Builder().withEntityClass(TestEntity.class).buildUnitTestRule();

  private final DurationConverter converter = new DurationConverter();

  @Test
  public void testNulls() {
    assertThat(converter.convertToDatabaseColumn(null)).isNull();
    assertThat(converter.convertToEntityAttribute(null)).isNull();
  }

  @Test
  public void testRoundTrip() {
    TestEntity entity = new TestEntity(Duration.standardDays(6));
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(entity));
    assertThat(
            jpaTm()
                .transact(
                    () ->
                        jpaTm()
                            .getEntityManager()
                            .createNativeQuery(
                                "SELECT duration FROM \"TestEntity\" WHERE name = 'id'")
                            .getResultList()))
        .containsExactly(BigInteger.valueOf(Duration.standardDays(6).getMillis()));
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.duration).isEqualTo(Duration.standardDays(6));
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  @EntityForTesting
  public static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    Duration duration;

    public TestEntity() {}

    TestEntity(Duration duration) {
      this.duration = duration;
    }
  }
}
