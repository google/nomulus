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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.ImmutableObject;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.Type;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link AllocationTokenStatusTransitionUserType}. */
public class AllocationTokenStatusTransitionUserTypeTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpa =
      new JpaTestExtensions.Builder()
          .withEntityClass(AllocationTokenStatusTransitionConverterTestEntity.class)
          .buildUnitTestExtension();

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
    TimedTransitionProperty<TokenStatus> timedTransitionProperty =
        TimedTransitionProperty.fromValueMap(values);
    AllocationTokenStatusTransitionConverterTestEntity testEntity =
        new AllocationTokenStatusTransitionConverterTestEntity(timedTransitionProperty);
    persistResource(testEntity);
    AllocationTokenStatusTransitionConverterTestEntity persisted =
        tm().transact(
                () ->
                    tm().getEntityManager()
                        .find(AllocationTokenStatusTransitionConverterTestEntity.class, "id"));
    assertThat(persisted.timedTransitionProperty.toValueMap())
        .containsExactlyEntriesIn(timedTransitionProperty.toValueMap());
  }

  @Entity
  private static class AllocationTokenStatusTransitionConverterTestEntity extends ImmutableObject {

    @Id String name = "id";

    @Type(AllocationTokenStatusTransitionUserType.class)
    TimedTransitionProperty<TokenStatus> timedTransitionProperty;

    private AllocationTokenStatusTransitionConverterTestEntity() {}

    private AllocationTokenStatusTransitionConverterTestEntity(
        TimedTransitionProperty<TokenStatus> timedTransitionProperty) {
      this.timedTransitionProperty = timedTransitionProperty;
    }
  }
}
