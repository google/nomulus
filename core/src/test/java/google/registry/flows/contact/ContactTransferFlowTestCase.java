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

package google.registry.flows.contact;

import static com.google.common.base.Preconditions.checkState;
import static google.registry.testing.DatabaseHelper.newContactResource;
import static google.registry.testing.DatabaseHelper.persistContactWithPendingTransfer;
import static google.registry.testing.DatabaseHelper.persistResource;

import google.registry.flows.Flow;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactResource;
import google.registry.model.tld.Registry;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.AppEngineExtension;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base class for contact transfer flow unit tests.
 *
 * @param <F> the flow type
 * @param <R> the resource type
 */
abstract class ContactTransferFlowTestCase<F extends Flow, R extends EppResource>
    extends ResourceFlowTestCase<F, R> {

  // Transfer is requested on the 6th and expires on the 11th.
  // The "now" of this flow is on the 9th, 3 days in.

  private static final DateTime TRANSFER_REQUEST_TIME = DateTime.parse("2000-06-06T22:00:00.0Z");
  private static final DateTime TRANSFER_EXPIRATION_TIME =
      TRANSFER_REQUEST_TIME.plus(Registry.DEFAULT_TRANSFER_GRACE_PERIOD);
  private static final Duration TIME_SINCE_REQUEST = Duration.standardDays(3);

  protected ContactResource contact;

  ContactTransferFlowTestCase() {
    checkState(!Registry.DEFAULT_TRANSFER_GRACE_PERIOD.isShorterThan(TIME_SINCE_REQUEST));
    clock.setTo(TRANSFER_REQUEST_TIME.plus(TIME_SINCE_REQUEST));
  }

  @BeforeEach
  void beforeEachContactTransferFlowTestCase() {
    // Registrar ClientZ is used in tests that need another registrar that definitely doesn't own
    // the resources in question.
    persistResource(AppEngineExtension.makeRegistrar1().asBuilder().setClientId("ClientZ").build());
  }

  /** Adds a contact that has a pending transfer on it from TheRegistrar to NewRegistrar. */
  void setupContactWithPendingTransfer() {
    contact = persistContactWithPendingTransfer(
        newContactResource("sh8013"),
        TRANSFER_REQUEST_TIME,
        TRANSFER_EXPIRATION_TIME,
        TRANSFER_REQUEST_TIME);
  }

  /** Changes the transfer status on the persisted contact. */
  protected void changeTransferStatus(TransferStatus transferStatus) {
    contact = persistResource(
        contact.asBuilder()
            .setTransferData(
                contact.getTransferData().asBuilder().setTransferStatus(transferStatus).build())
            .build());
    clock.advanceOneMilli();
  }

  /** Changes the client ID that the flow will run as. */
  @Override
  protected void setClientIdForFlow(String clientId) {
    sessionMetadata.setClientId(clientId);
  }
}
