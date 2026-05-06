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
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.ImmutableObject;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import org.hibernate.annotations.Type;
import org.joda.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link BillingCostTransitionUserType}. */
public class BillingCostTransitionUserTypeTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpa =
      new JpaTestExtensions.Builder().withEntityClass(TestEntity.class).buildUnitTestExtension();

  private static final ImmutableSortedMap<Instant, Money> values =
      ImmutableSortedMap.of(
          START_INSTANT,
          Money.of(USD, 8),
          Instant.parse("2001-01-01T00:00:00.0Z"),
          Money.of(USD, 0));

  @Test
  void roundTripConversion_returnsSameTimedTransitionProperty() {
    TimedTransitionProperty<Money> timedTransitionProperty =
        TimedTransitionProperty.fromValueMap(values);
    TestEntity testEntity = new TestEntity(timedTransitionProperty);
    persistResource(testEntity);
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.timedTransitionProperty.toValueMap())
        .containsExactlyEntriesIn(timedTransitionProperty.toValueMap());
  }

  @Entity(name = "TestEntity")
  private static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    @Type(BillingCostTransitionUserType.class)
    @Column(columnDefinition = "hstore")
    TimedTransitionProperty<Money> timedTransitionProperty;

    private TestEntity() {}

    private TestEntity(TimedTransitionProperty<Money> timedTransitionProperty) {
      this.timedTransitionProperty = timedTransitionProperty;
    }
  }
}
