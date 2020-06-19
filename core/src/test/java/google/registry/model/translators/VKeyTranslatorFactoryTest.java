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

package google.registry.model.translators;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.LoadException;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.impl.NullProperty;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.DomainBase;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.poll.PollMessage;
import google.registry.persistence.VKey;
import google.registry.testing.AppEngineRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class VKeyTranslatorFactoryTest {

  @RegisterExtension
  public final AppEngineRule appEngine =
      AppEngineRule.builder()
          .withDatastore()
          .withOfyTestEntities(TestEntity.class, UnsupportedTestEntity.class)
          .build();

  public VKeyTranslatorFactoryTest() {}

  @Test
  void testEntityWithVKeyCreate() {
    // Creating an objectify key instead of a datastore key as this should get a correctly formatted
    // key path.
    DomainBase domain = newDomainBase("example.com", "ROID-1", persistActiveContact("contact-1"));
    Key<DomainBase> key = Key.create(domain);
    VKey<DomainBase> vkey = VKeyTranslatorFactory.createVKey(key, NullProperty.INSTANCE);
    assertThat(vkey.getKind()).isEqualTo(DomainBase.class);
    assertThat(vkey.getOfyKey()).isEqualTo(key);
    assertThat(vkey.getSqlKey()).isEqualTo("ROID-1");
  }

  @Test
  void testEntityWithoutVKeyCreate() {
    CommitLogBucket bucket = new CommitLogBucket.Builder().build();
    Key<CommitLogBucket> key = Key.create(bucket);
    VKey<CommitLogBucket> vkey = VKeyTranslatorFactory.createVKey(key, NullProperty.INSTANCE);
    assertThat(vkey.getKind()).isEqualTo(CommitLogBucket.class);
    assertThat(vkey.getOfyKey()).isEqualTo(key);
    assertThat(vkey.maybeGetSqlKey().isPresent()).isFalse();
  }

  @Test
  void testUrlSafeKey() {
    // Creating an objectify key instead of a datastore key as this should get a correctly formatted
    // key path.
    DomainBase domain = newDomainBase("example.com", "ROID-1", persistActiveContact("contact-1"));
    Key<DomainBase> key = Key.create(domain);
    VKey<DomainBase> vkey =
        (VKey<DomainBase>) VKeyTranslatorFactory.createVKey(key.getString(), NullProperty.INSTANCE);
    assertThat(vkey.getKind()).isEqualTo(DomainBase.class);
    assertThat(vkey.getOfyKey()).isEqualTo(key);
    assertThat(vkey.getSqlKey()).isEqualTo("ROID-1");
  }

  @Test
  void testEntityWithSubclasses() {
    VKey<PollMessage.OneTime> pollMessageVKey = VKey.createOfy(PollMessage.OneTime.class, 1L);
    VKey<BillingEvent.Recurring> billingEventVKey =
        VKey.createOfy(BillingEvent.Recurring.class, 2L);
    TestEntity testEntity = new TestEntity(pollMessageVKey, billingEventVKey);
    tm().transact(() -> tm().saveNew(testEntity));
    TestEntity persisted = tm().transact(() -> tm().load(testEntity.key()));
    assertThat(persisted).isEqualTo(testEntity);
    assertThat(persisted.pollMessageVKey).isEqualTo(pollMessageVKey);
    assertThat(persisted.billingEventVKey).isEqualTo(billingEventVKey);
  }

  @Test
  void testEntityWithUnsupportedVKey() {
    VKey<PollMessage.OneTime> pollMessageVKey = VKey.createOfy(PollMessage.OneTime.class, 1L);
    UnsupportedTestEntity testEntity = new UnsupportedTestEntity(pollMessageVKey);
    tm().transact(() -> tm().saveNew(testEntity));
    assertThrows(
        LoadException.class,
        () -> tm().transact(() -> tm().load(testEntity.key())),
        "Error loading UnsupportedTestEntity(\"testEntity\"): Unknown Key type: PollMessage");
  }

  @Entity(name = "TestEntity")
  private static class TestEntity extends ImmutableObject {
    @Id private String name = "testEntity";

    private VKey<PollMessage.OneTime> pollMessageVKey;

    private VKey<BillingEvent.Recurring> billingEventVKey;

    private TestEntity() {}

    private TestEntity(
        VKey<PollMessage.OneTime> pollMessageVKey, VKey<BillingEvent.Recurring> billingEventVKey) {
      this.pollMessageVKey = pollMessageVKey;
      this.billingEventVKey = billingEventVKey;
    }

    public VKey<TestEntity> key() {
      return VKey.create(TestEntity.class, name, Key.create(this));
    }
  }

  @Entity(name = "UnsupportedTestEntity")
  private static class UnsupportedTestEntity extends ImmutableObject {
    @Id private String name = "testEntity";

    VKey<? extends PollMessage> pollMessageVKey;

    private UnsupportedTestEntity() {}

    private UnsupportedTestEntity(VKey<PollMessage.OneTime> pollMessageVKey) {
      this.pollMessageVKey = pollMessageVKey;
    }

    public VKey<TestEntity> key() {
      return VKey.create(TestEntity.class, name, Key.create(this));
    }
  }
}
