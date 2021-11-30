// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.common;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.billing.BillingEvent.Cancellation;
import google.registry.model.billing.BillingEvent.Modification;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.host.HostResource;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.EppResourceIndexBucket;
import google.registry.model.index.ForeignKeyIndex.ForeignKeyContactIndex;
import google.registry.model.index.ForeignKeyIndex.ForeignKeyDomainIndex;
import google.registry.model.index.ForeignKeyIndex.ForeignKeyHostIndex;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.ofy.CommitLogCheckpoint;
import google.registry.model.ofy.CommitLogCheckpointRoot;
import google.registry.model.ofy.CommitLogManifest;
import google.registry.model.ofy.CommitLogMutation;
import google.registry.model.poll.PollMessage;
import google.registry.model.rde.RdeRevision;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.replay.LastSqlTransaction;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.server.Lock;
import google.registry.model.server.ServerSecret;
import google.registry.model.tld.Registry;
import google.registry.testing.TestObject;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ClassPathManager}. */
public class ClassPathManagerTest {
  @Test
  void getClass_classInClassRegistry_returnsClass() throws ClassNotFoundException {
    // Below are all classes supported by CLASS_REGISTRY. THis test breaks if someone changes a
    // classname without preserving the original name.

    assertThat(ClassPathManager.getClass("ForeignKeyContactIndex"))
        .isEqualTo(ForeignKeyContactIndex.class);
    assertThat(ClassPathManager.getClass("Modification")).isEqualTo(Modification.class);
    assertThat(ClassPathManager.getClass("CommitLogCheckpoint"))
        .isEqualTo(CommitLogCheckpoint.class);
    assertThat(ClassPathManager.getClass("CommitLogManifest")).isEqualTo(CommitLogManifest.class);
    assertThat(ClassPathManager.getClass("AllocationToken")).isEqualTo(AllocationToken.class);
    assertThat(ClassPathManager.getClass("OneTime")).isEqualTo(OneTime.class);
    assertThat(ClassPathManager.getClass("Cursor")).isEqualTo(Cursor.class);
    assertThat(ClassPathManager.getClass("RdeRevision")).isEqualTo(RdeRevision.class);
    assertThat(ClassPathManager.getClass("HostResource")).isEqualTo(HostResource.class);
    assertThat(ClassPathManager.getClass("Recurring")).isEqualTo(Recurring.class);
    assertThat(ClassPathManager.getClass("Registrar")).isEqualTo(Registrar.class);
    assertThat(ClassPathManager.getClass("ContactResource")).isEqualTo(ContactResource.class);
    assertThat(ClassPathManager.getClass("Cancellation")).isEqualTo(Cancellation.class);
    assertThat(ClassPathManager.getClass("RegistrarContact")).isEqualTo(RegistrarContact.class);
    assertThat(ClassPathManager.getClass("CommitLogBucket")).isEqualTo(CommitLogBucket.class);
    assertThat(ClassPathManager.getClass("LastSqlTransaction")).isEqualTo(LastSqlTransaction.class);
    assertThat(ClassPathManager.getClass("CommitLogCheckpointRoot"))
        .isEqualTo(CommitLogCheckpointRoot.class);
    assertThat(ClassPathManager.getClass("GaeUserIdConverter")).isEqualTo(GaeUserIdConverter.class);
    assertThat(ClassPathManager.getClass("EppResourceIndexBucket"))
        .isEqualTo(EppResourceIndexBucket.class);
    assertThat(ClassPathManager.getClass("Registry")).isEqualTo(Registry.class);
    assertThat(ClassPathManager.getClass("EntityGroupRoot")).isEqualTo(EntityGroupRoot.class);
    assertThat(ClassPathManager.getClass("Lock")).isEqualTo(Lock.class);
    assertThat(ClassPathManager.getClass("DomainBase")).isEqualTo(DomainBase.class);
    assertThat(ClassPathManager.getClass("CommitLogMutation")).isEqualTo(CommitLogMutation.class);
    assertThat(ClassPathManager.getClass("HistoryEntry")).isEqualTo(HistoryEntry.class);
    assertThat(ClassPathManager.getClass("PollMessage")).isEqualTo(PollMessage.class);
    assertThat(ClassPathManager.getClass("ForeignKeyHostIndex"))
        .isEqualTo(ForeignKeyHostIndex.class);
    assertThat(ClassPathManager.getClass("ServerSecret")).isEqualTo(ServerSecret.class);
    assertThat(ClassPathManager.getClass("EppResourceIndex")).isEqualTo(EppResourceIndex.class);
    assertThat(ClassPathManager.getClass("ForeignKeyDomainIndex"))
        .isEqualTo(ForeignKeyDomainIndex.class);
  }

  @Test
  void getClass_classNotInClassRegistry_throwsException() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> ClassPathManager.getClass("DomainHistory"));
    assertThat(thrown).hasMessageThat().contains("Class not found in class registry");
  }

  @Test
  void addTestEntityClass_success() {
    assertThrows(IllegalArgumentException.class, () -> ClassPathManager.getClass("TestObject"));
    ClassPathManager.addTestEntityClass(TestObject.class);
    assertThat(ClassPathManager.getClass("TestObject")).isEqualTo(TestObject.class);
  }
}
