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

package google.registry.persistence;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.common.EntityGroupRoot;
import google.registry.model.domain.DomainBase;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.BillingVKey.BillingEventVKey;
import google.registry.persistence.BillingVKey.BillingRecurrenceVKey;
import google.registry.schema.replay.EntityTest.EntityForTesting;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import javax.persistence.Transient;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit test for {@link BillingVKey}. */
@DualDatabaseTest
class BillingVKeyTest {
  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withOfyTestEntities(BillingVKeyTestEntity.class)
          .withJpaUnitTestEntities(BillingVKeyTestEntity.class)
          .build();

  @TestOfyAndSql
  void testRestoreSymmetricVKey() {
    Key<HistoryEntry> domainHistoryKey =
        Key.create(Key.create(DomainBase.class, "domainRepoId"), HistoryEntry.class, 10L);

    Key<BillingEvent.OneTime> oneTimeOfyKey =
        Key.create(domainHistoryKey, BillingEvent.OneTime.class, 100L);
    VKey<BillingEvent.OneTime> oneTimeVKey =
        VKey.create(BillingEvent.OneTime.class, 100L, oneTimeOfyKey);

    Key<BillingEvent.Recurring> recurringOfyKey =
        Key.create(domainHistoryKey, BillingEvent.Recurring.class, 200L);
    VKey<BillingEvent.Recurring> recurringVKey =
        VKey.create(BillingEvent.Recurring.class, 200L, recurringOfyKey);

    BillingVKeyTestEntity original = new BillingVKeyTestEntity(oneTimeVKey, recurringVKey);
    tm().transact(() -> tm().insert(original));
    BillingVKeyTestEntity persisted = tm().transact(() -> tm().load(original.createVKey()));

    assertThat(persisted).isEqualTo(original);
    assertThat(persisted.getBillingEventVKey()).isEqualTo(oneTimeVKey);
    assertThat(persisted.getBillingRecurrenceVKey()).isEqualTo(recurringVKey);
  }

  @TestOfyAndSql
  void testHandleNullVKeyCorrectly() {
    BillingVKeyTestEntity original = new BillingVKeyTestEntity(null, null);
    tm().transact(() -> tm().insert(original));
    BillingVKeyTestEntity persisted = tm().transact(() -> tm().load(original.createVKey()));

    assertThat(persisted).isEqualTo(original);
  }

  @EntityForTesting
  @Entity
  @javax.persistence.Entity
  private static class BillingVKeyTestEntity extends ImmutableObject {
    @Transient @Parent Key<EntityGroupRoot> parent = getCrossTldKey();

    @Id @javax.persistence.Id String id = "id";

    BillingEventVKey billingEventVKey;

    BillingRecurrenceVKey billingRecurrenceVKey;

    BillingVKeyTestEntity() {}

    BillingVKeyTestEntity(
        VKey<BillingEvent.OneTime> onetime, VKey<BillingEvent.Recurring> recurring) {
      this.billingEventVKey = BillingEventVKey.create(onetime);
      this.billingRecurrenceVKey = BillingRecurrenceVKey.create(recurring);
    }

    VKey<BillingEvent.OneTime> getBillingEventVKey() {
      return billingEventVKey.createVKey();
    }

    VKey<BillingEvent.Recurring> getBillingRecurrenceVKey() {
      return billingRecurrenceVKey.createVKey();
    }

    VKey<BillingVKeyTestEntity> createVKey() {
      return VKey.create(
          BillingVKeyTestEntity.class, id, Key.create(parent, BillingVKeyTestEntity.class, id));
    }
  }
}
