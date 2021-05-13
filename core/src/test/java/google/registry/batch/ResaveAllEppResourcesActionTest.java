// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.batch;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistContactWithPendingTransfer;
import static org.joda.time.DateTimeZone.UTC;

import google.registry.model.contact.ContactResource;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.FakeResponse;
import google.registry.testing.mapreduce.MapreduceTestCase;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ResaveAllEppResourcesAction}. */
class ResaveAllEppResourcesActionTest extends MapreduceTestCase<ResaveAllEppResourcesAction> {

  @BeforeEach
  void beforeEach() {
    action = new ResaveAllEppResourcesAction();
    action.mrRunner = makeDefaultRunner();
    action.response = new FakeResponse();
  }

  private void runMapreduce() throws Exception {
    action.run();
    executeTasksUntilEmpty("mapreduce");
  }

  @Test
  void test_mapreduceSuccessfullyResavesEntity() throws Exception {
    ContactResource contact = persistActiveContact("test123");
    DateTime creationTime = contact.getUpdateTimestamp().getTimestamp();
    assertThat(auditedOfy().load().entity(contact).now().getUpdateTimestamp().getTimestamp())
        .isEqualTo(creationTime);
    auditedOfy().clearSessionCache();
    runMapreduce();
    assertThat(auditedOfy().load().entity(contact).now().getUpdateTimestamp().getTimestamp())
        .isGreaterThan(creationTime);
  }

  @Test
  void test_fastMode_doesNotResaveEntityWithNoChanges() throws Exception {
    ContactResource contact = persistActiveContact("test123");
    DateTime creationTime = contact.getUpdateTimestamp().getTimestamp();
    assertThat(auditedOfy().load().entity(contact).now().getUpdateTimestamp().getTimestamp())
        .isEqualTo(creationTime);
    auditedOfy().clearSessionCache();
    action.isFast = true;
    runMapreduce();
    assertThat(auditedOfy().load().entity(contact).now().getUpdateTimestamp().getTimestamp())
        .isEqualTo(creationTime);
  }

  @Test
  void test_mapreduceResolvesPendingTransfer() throws Exception {
    DateTime now = DateTime.now(UTC);
    // Set up a contact with a transfer that implicitly completed five days ago.
    ContactResource contact =
        persistContactWithPendingTransfer(
            persistActiveContact("meh789"),
            now.minusDays(10),
            now.minusDays(10),
            now.minusDays(10));
    assertThat(contact.getTransferData().getTransferStatus()).isEqualTo(TransferStatus.PENDING);
    runMapreduce();

    auditedOfy().clearSessionCache();
    // The transfer should be effective after the contact is re-saved, as it should've been
    // projected to the current time.
    ContactResource resavedContact = auditedOfy().load().entity(contact).now();
    assertThat(resavedContact.getTransferData().getTransferStatus())
        .isEqualTo(TransferStatus.SERVER_APPROVED);
  }
}
