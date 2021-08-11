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

import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.ImmutableObject;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.model.tld.Registry.TldState;
import google.registry.model.tld.Registry.TldStateTransition;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestExtension;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link TldStateTransitionConverter}. */
class TldStateTransitionConverterTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpa =
      new JpaTestRules.Builder()
          .withInitScript("sql/flyway/V14__load_extension_for_hstore.sql")
          .withEntityClass(TestEntity.class)
          .buildUnitTestRule();

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
    TimedTransitionProperty<TldState, TldStateTransition> timedTransitionProperty =
        TimedTransitionProperty.fromValueMap(values, TldStateTransition.class);
    TestEntity testEntity = new TestEntity(timedTransitionProperty);
    jpaTm().transact(() -> jpaTm().insert(testEntity));
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.timedTransitionProperty).containsExactlyEntriesIn(timedTransitionProperty);
  }

  @Entity(name = "TestEntity")
  private static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    TimedTransitionProperty<TldState, TldStateTransition> timedTransitionProperty;

    private TestEntity() {}

    private TestEntity(
        TimedTransitionProperty<TldState, TldStateTransition> timedTransitionProperty) {
      this.timedTransitionProperty = timedTransitionProperty;
    }
  }
}
