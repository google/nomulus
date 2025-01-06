// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.ImmutableObject;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.model.tld.Tld.TldState;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.Type;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link TldStateTransitionUserType}. */
class TldStateTransitionUserTypeTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpa =
      new JpaTestExtensions.Builder().withEntityClass(TestEntity.class).buildUnitTestExtension();

  private static final ImmutableSortedMap<DateTime, TldState> values =
      ImmutableSortedMap.of(
          START_OF_TIME,
          TldState.PREDELEGATION,
          DateTime.parse("2001-01-01T00:00:00.0Z"),
          TldState.QUIET_PERIOD,
          DateTime.parse("2002-01-01T00:00:00.0Z"),
          TldState.PDT,
          DateTime.parse("2003-01-01T00:00:00.0Z"),
          TldState.GENERAL_AVAILABILITY);

  @Test
  void roundTripConversion_returnsSameTimedTransitionProperty() {
    TimedTransitionProperty<TldState> timedTransitionProperty =
        TimedTransitionProperty.fromValueMap(values);
    TestEntity testEntity = new TestEntity(timedTransitionProperty);
    insertInDb(testEntity);
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.timedTransitionProperty.toValueMap())
        .containsExactlyEntriesIn(timedTransitionProperty.toValueMap());
  }

  @Entity(name = "TestEntity")
  private static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    @Type(TldStateTransitionUserType.class)
    TimedTransitionProperty<TldState> timedTransitionProperty;

    private TestEntity() {}

    private TestEntity(TimedTransitionProperty<TldState> timedTransitionProperty) {
      this.timedTransitionProperty = timedTransitionProperty;
    }
  }
}
