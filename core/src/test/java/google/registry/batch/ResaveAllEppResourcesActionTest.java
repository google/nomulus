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
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistContactWithPendingTransfer;
import static org.joda.time.DateTimeZone.UTC;

import google.registry.model.contact.ContactResource;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.FakeResponse;
import google.registry.testing.mapreduce.MapreduceTestCase;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ResaveAllEppResourcesAction}. */
@RunWith(JUnit4.class)
public class ResaveAllEppResourcesActionTest
    extends MapreduceTestCase<ResaveAllEppResourcesAction> {

  @Before
  public void init() {
    action = new ResaveAllEppResourcesAction();
    action.mrRunner = makeDefaultRunner();
    action.response = new FakeResponse();
  }

  private void runMapreduce() throws Exception {
    action.run();
    executeTasksUntilEmpty("mapreduce");
  }

  @Test
  public void test_mapreduceSuccessfullyResavesEntity() throws Exception {
    ContactResource contact = persistActiveContact("test123");
    DateTime creationTime = contact.getUpdateTimestamp().getTimestamp();
    assertThat(ofy().load().entity(contact).now().getUpdateTimestamp().getTimestamp())
        .isEqualTo(creationTime);
    ofy().clearSessionCache();
    runMapreduce();
    assertThat(ofy().load().entity(contact).now().getUpdateTimestamp().getTimestamp())
        .isGreaterThan(creationTime);
  }

  @Test
  public void test_mapreduceResolvesPendingTransfer() throws Exception {
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

    ofy().clearSessionCache();
    // The transfer should be effective after the contact is re-saved, as it should've been
    // projected to the current time.
    ContactResource resavedContact = ofy().load().entity(contact).now();
    assertThat(resavedContact.getTransferData().getTransferStatus())
        .isEqualTo(TransferStatus.SERVER_APPROVED);
  }
}
