// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.flows.ResourceFlowUtils.approvePendingTransfer;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyOptionalAuthInfoForResource;
import static google.registry.flows.ResourceFlowUtils.verifyResourceOwnership;
import static google.registry.flows.contact.ContactFlowUtils.createGainingTransferPollMessage;
import static google.registry.flows.contact.ContactFlowUtils.createTransferResponse;
import static google.registry.model.eppoutput.Result.Code.SUCCESS;
import static google.registry.model.ofy.ObjectifyService.ofy;

import com.google.common.base.Optional;
import com.googlecode.objectify.Key;
import google.registry.flows.EppException;
import google.registry.flows.FlowModule.ClientId;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.LoggedInFlow;
import google.registry.flows.TransactionalFlow;
import google.registry.flows.exceptions.NotPendingTransferException;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.EppOutput;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import javax.inject.Inject;

/**
 * An EPP flow that approves a pending transfer on a contact.
 *
 * <p>The "gaining" registrar requests a transfer from the "losing" (aka current) registrar. The
 * losing registrar has a "transfer" time period to respond (by default five days) after which the
 * transfer is automatically approved. Within that window, this flow allows the losing client to
 * explicitly approve the transfer request, which then becomes effective immediately.
 *
 * @error {@link google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link google.registry.flows.exceptions.NotPendingTransferException}
 */
public final class ContactTransferApproveFlow extends LoggedInFlow implements TransactionalFlow {

  @Inject ResourceCommand resourceCommand;
  @Inject @ClientId String clientId;
  @Inject @TargetId String targetId;
  @Inject Optional<AuthInfo> authInfo;
  @Inject HistoryEntry.Builder historyBuilder;
  @Inject ContactTransferApproveFlow() {}

  @Override
  protected final void initLoggedInFlow() throws EppException {
    registerExtensions(MetadataExtension.class);
  }

  /**
   * <p>The logic in this flow, which handles client approvals, very closely parallels the logic in
   * {@link ContactResource#cloneProjectedAtTime} which handles implicit server approvals.
   */
  @Override
  public final EppOutput run() throws EppException {
    ContactResource existingContact = loadAndVerifyExistence(ContactResource.class, targetId, now);
    verifyOptionalAuthInfoForResource(authInfo, existingContact);
    TransferData transferData = existingContact.getTransferData();
    if (transferData.getTransferStatus() != TransferStatus.PENDING) {
      throw new NotPendingTransferException(targetId);
    }
    verifyResourceOwnership(clientId, existingContact);
    ContactResource newContact =
        approvePendingTransfer(existingContact, TransferStatus.CLIENT_APPROVED, now);
    HistoryEntry historyEntry = historyBuilder
        .setType(HistoryEntry.Type.CONTACT_TRANSFER_APPROVE)
        .setModificationTime(now)
        .setParent(Key.create(existingContact))
        .build();
    // Create a poll message for the gaining client.
    PollMessage gainingPollMessage =
        createGainingTransferPollMessage(targetId, newContact.getTransferData(), historyEntry);
    ofy().save().<Object>entities(newContact, historyEntry, gainingPollMessage);
    // Delete the billing event and poll messages that were written in case the transfer would have
    // been implicitly server approved.
    ofy().delete().keys(transferData.getServerApproveEntities());
    return createOutput(SUCCESS, createTransferResponse(targetId, newContact.getTransferData()));
  }
}
