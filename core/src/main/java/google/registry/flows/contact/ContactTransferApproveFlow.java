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

import static google.registry.flows.FlowUtils.validateRegistrarIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyHasPendingTransfer;
import static google.registry.flows.ResourceFlowUtils.verifyOptionalAuthInfo;
import static google.registry.flows.ResourceFlowUtils.verifyResourceOwnership;
import static google.registry.flows.contact.ContactFlowUtils.createGainingTransferPollMessage;
import static google.registry.flows.contact.ContactFlowUtils.createTransferResponse;
import static google.registry.model.ResourceTransferUtils.approvePendingTransfer;
import static google.registry.model.reporting.HistoryEntry.Type.CONTACT_TRANSFER_APPROVE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.TransactionalFlow;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import google.registry.model.transfer.TransferStatus;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * An EPP flow that approves a pending transfer on a contact.
 *
 * <p>The "gaining" registrar requests a transfer from the "losing" (aka current) registrar. The
 * losing registrar has a "transfer" time period to respond (by default five days) after which the
 * transfer is automatically approved. Within that window, this flow allows the losing client to
 * explicitly approve the transfer request, which then becomes effective immediately.
 *
 * @error {@link google.registry.flows.EppException.ReadOnlyModeEppException}
 * @error {@link google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link google.registry.flows.exceptions.NotPendingTransferException}
 */
@ReportingSpec(ActivityReportField.CONTACT_TRANSFER_APPROVE)
public final class ContactTransferApproveFlow implements TransactionalFlow {

  @Inject ResourceCommand resourceCommand;
  @Inject ExtensionManager extensionManager;
  @Inject @RegistrarId String registrarId;
  @Inject @TargetId String targetId;
  @Inject Optional<AuthInfo> authInfo;
  @Inject ContactHistory.Builder historyBuilder;
  @Inject EppResponse.Builder responseBuilder;
  @Inject ContactTransferApproveFlow() {}

  /**
   * <p>The logic in this flow, which handles client approvals, very closely parallels the logic in
   * {@link ContactResource#cloneProjectedAtTime} which handles implicit server approvals.
   */
  @Override
  public final EppResponse run() throws EppException {
    extensionManager.register(MetadataExtension.class);
    extensionManager.validate();
    validateRegistrarIsLoggedIn(registrarId);
    DateTime now = tm().getTransactionTime();
    ContactResource existingContact = loadAndVerifyExistence(ContactResource.class, targetId, now);
    verifyOptionalAuthInfo(authInfo, existingContact);
    verifyHasPendingTransfer(existingContact);
    verifyResourceOwnership(registrarId, existingContact);
    ContactResource newContact =
        approvePendingTransfer(existingContact, TransferStatus.CLIENT_APPROVED, now);
    ContactHistory contactHistory =
        historyBuilder.setType(CONTACT_TRANSFER_APPROVE).setContact(newContact).build();
    // Create a poll message for the gaining client.
    PollMessage gainingPollMessage =
        createGainingTransferPollMessage(
            targetId, newContact.getTransferData(), now, Key.create(contactHistory));
    tm().insertAll(ImmutableSet.of(contactHistory, gainingPollMessage));
    tm().update(newContact);
    // Delete the billing event and poll messages that were written in case the transfer would have
    // been implicitly server approved.
    tm().delete(existingContact.getTransferData().getServerApproveEntities());
    return responseBuilder
        .setResData(createTransferResponse(targetId, newContact.getTransferData()))
        .build();
  }
}
