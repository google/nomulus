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
import static google.registry.model.domain.token.AllocationToken.TokenStatus.ENDED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.NOT_STARTED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.VALID;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.ImmutableObject;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.domain.token.AllocationToken.TokenStatusTransition;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestExtension;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link AllocationTokenStatusTransitionConverter}. */
public class AllocationTokenStatusTransitionConverterTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpa =
      new JpaTestRules.Builder()
          .withEntityClass(AllocationTokenStatusTransitionConverterTestEntity.class)
          .buildUnitTestRule();

  private static final ImmutableSortedMap<DateTime, TokenStatus> values =
      ImmutableSortedMap.of(
          START_OF_TIME,
          NOT_STARTED,
          DateTime.parse("2001-01-01T00:00:00.0Z"),
          VALID,
          DateTime.parse("2002-01-01T00:00:00.0Z"),
          ENDED);

  @Test
  void roundTripConversion_returnsSameTimedTransitionProperty() {
    TimedTransitionProperty<TokenStatus, TokenStatusTransition> timedTransitionProperty =
        TimedTransitionProperty.fromValueMap(values, TokenStatusTransition.class);
    AllocationTokenStatusTransitionConverterTestEntity testEntity =
        new AllocationTokenStatusTransitionConverterTestEntity(timedTransitionProperty);
    insertInDb(testEntity);
    AllocationTokenStatusTransitionConverterTestEntity persisted =
        jpaTm()
            .transact(
                () ->
                    jpaTm()
                        .getEntityManager()
                        .find(AllocationTokenStatusTransitionConverterTestEntity.class, "id"));
    assertThat(persisted.timedTransitionProperty).containsExactlyEntriesIn(timedTransitionProperty);
  }

  @Entity
  private static class AllocationTokenStatusTransitionConverterTestEntity extends ImmutableObject {

    @Id String name = "id";

    TimedTransitionProperty<TokenStatus, TokenStatusTransition> timedTransitionProperty;

    private AllocationTokenStatusTransitionConverterTestEntity() {}

    private AllocationTokenStatusTransitionConverterTestEntity(
        TimedTransitionProperty<TokenStatus, TokenStatusTransition> timedTransitionProperty) {
      this.timedTransitionProperty = timedTransitionProperty;
    }
  }
}
