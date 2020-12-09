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
import google.registry.model.common.EntityGroupRoot;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.PollMessageVKey.ContactPollMessageVKey;
import google.registry.persistence.PollMessageVKey.DomainPollMessageVKey;
import google.registry.schema.replay.EntityTest.EntityForTesting;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Transient;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit test for {@link PollMessageVKey}. */
@DualDatabaseTest
public class PollMessageVKeyTest {
  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withOfyTestEntities(PollMessageVKeyTestEntity.class)
          .withJpaUnitTestEntities(PollMessageVKeyTestEntity.class)
          .build();

  @TestOfyAndSql
  void testRestoreSymmetricVKey() {
    Key<HistoryEntry> domainHistoryKey =
        Key.create(Key.create(DomainBase.class, "domainRepoId"), HistoryEntry.class, 10L);
    Key<PollMessage.Autorenew> domainOfyKey =
        Key.create(domainHistoryKey, PollMessage.Autorenew.class, 100L);
    VKey<PollMessage.Autorenew> domainVKey =
        VKey.create(PollMessage.Autorenew.class, 100L, domainOfyKey);

    Key<HistoryEntry> contactHistoryKey =
        Key.create(Key.create(ContactResource.class, "contactRepoId"), HistoryEntry.class, 20L);
    Key<PollMessage.OneTime> contactOfyKey =
        Key.create(contactHistoryKey, PollMessage.OneTime.class, 200L);
    VKey<PollMessage.OneTime> contactVKey =
        VKey.create(PollMessage.OneTime.class, 200L, contactOfyKey);

    PollMessageVKeyTestEntity original = new PollMessageVKeyTestEntity(domainVKey, contactVKey);
    tm().transact(() -> tm().insert(original));
    PollMessageVKeyTestEntity persisted = tm().transact(() -> tm().load(original.createVKey()));

    assertThat(persisted).isEqualTo(original);
    assertThat(persisted.getDomainPollMessageVKey()).isEqualTo(domainVKey);
    assertThat(persisted.getContactPollMessageVKey()).isEqualTo(contactVKey);
  }

  @TestOfyAndSql
  void testHandleNullVKeyCorrectly() {
    PollMessageVKeyTestEntity original = new PollMessageVKeyTestEntity(null, null);
    tm().transact(() -> tm().insert(original));
    PollMessageVKeyTestEntity persisted = tm().transact(() -> tm().load(original.createVKey()));

    assertThat(persisted).isEqualTo(original);
  }

  @EntityForTesting
  @Entity
  @javax.persistence.Entity
  private static class PollMessageVKeyTestEntity extends ImmutableObject {
    @Transient @Parent Key<EntityGroupRoot> parent = getCrossTldKey();

    @Id @javax.persistence.Id String id = "id";

    @AttributeOverrides({
      @AttributeOverride(name = "repoId", column = @Column(name = "domain_repo_id")),
      @AttributeOverride(name = "historyRevisionId", column = @Column(name = "domain_history_id")),
      @AttributeOverride(name = "pollMessageId", column = @Column(name = "domain_poll_message_id"))
    })
    DomainPollMessageVKey domainPollMessageVKey;

    @AttributeOverrides({
      @AttributeOverride(name = "repoId", column = @Column(name = "contact_repo_id")),
      @AttributeOverride(name = "historyRevisionId", column = @Column(name = "contact_history_id")),
      @AttributeOverride(name = "pollMessageId", column = @Column(name = "contact_poll_message_id"))
    })
    ContactPollMessageVKey contactPollMessageVKey;

    PollMessageVKeyTestEntity() {}

    PollMessageVKeyTestEntity(
        VKey<PollMessage.Autorenew> autorenew, VKey<PollMessage.OneTime> onetime) {
      this.domainPollMessageVKey =
          autorenew == null ? null : DomainPollMessageVKey.create(autorenew);
      this.contactPollMessageVKey = onetime == null ? null : ContactPollMessageVKey.create(onetime);
    }

    VKey<PollMessage.Autorenew> getDomainPollMessageVKey() {
      return domainPollMessageVKey.toAutoRenewVKey();
    }

    VKey<PollMessage.OneTime> getContactPollMessageVKey() {
      return contactPollMessageVKey.toOneTimeVKey();
    }

    VKey<PollMessageVKeyTestEntity> createVKey() {
      return VKey.create(
          PollMessageVKeyTestEntity.class,
          id,
          Key.create(parent, PollMessageVKeyTestEntity.class, id));
    }
  }
}
