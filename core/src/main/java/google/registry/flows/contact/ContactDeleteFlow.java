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
import static google.registry.flows.ResourceFlowUtils.checkLinkedDomains;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyNoDisallowedStatuses;
import static google.registry.flows.ResourceFlowUtils.verifyOptionalAuthInfo;
import static google.registry.flows.ResourceFlowUtils.verifyResourceOwnership;
import static google.registry.model.ResourceTransferUtils.denyPendingTransfer;
import static google.registry.model.ResourceTransferUtils.handlePendingTransferOnDelete;
import static google.registry.model.eppoutput.Result.Code.SUCCESS;
import static google.registry.model.eppoutput.Result.Code.SUCCESS_WITH_ACTION_PENDING;
import static google.registry.model.transfer.TransferStatus.SERVER_CANCELLED;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableSet;
import google.registry.batch.AsyncTaskEnqueuer;
import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.TransactionalFlow;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.eppoutput.Result.Code;
import google.registry.model.reporting.HistoryEntry.Type;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * An EPP flow that deletes a contact.
 *
 * <p>Contacts that are in use by any domain cannot be deleted. The flow may return immediately if a
 * quick smoke check determines that deletion is impossible due to an existing reference. However, a
 * successful delete will always be asynchronous, as all existing domains must be checked for
 * references to the host before the deletion is allowed to proceed. A poll message will be written
 * with the success or failure message when the process is complete.
 *
 * @error {@link google.registry.flows.EppException.ReadOnlyModeEppException}
 * @error {@link google.registry.flows.FlowUtils.NotLoggedInException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException}
 * @error {@link google.registry.flows.exceptions.ResourceStatusProhibitsOperationException}
 * @error {@link google.registry.flows.exceptions.ResourceToDeleteIsReferencedException}
 */
@ReportingSpec(ActivityReportField.CONTACT_DELETE)
public final class ContactDeleteFlow implements TransactionalFlow {

  private static final ImmutableSet<StatusValue> DISALLOWED_STATUSES =
      ImmutableSet.of(
          StatusValue.CLIENT_DELETE_PROHIBITED,
          StatusValue.PENDING_DELETE,
          StatusValue.SERVER_DELETE_PROHIBITED);

  @Inject ExtensionManager extensionManager;
  @Inject @RegistrarId String registrarId;
  @Inject @TargetId String targetId;
  @Inject Trid trid;
  @Inject @Superuser boolean isSuperuser;
  @Inject Optional<AuthInfo> authInfo;
  @Inject ContactHistory.Builder historyBuilder;
  @Inject AsyncTaskEnqueuer asyncTaskEnqueuer;
  @Inject EppResponse.Builder responseBuilder;

  @Inject
  ContactDeleteFlow() {}

  @Override
  public final EppResponse run() throws EppException {
    extensionManager.register(MetadataExtension.class);
    extensionManager.validate();
    validateRegistrarIsLoggedIn(registrarId);
    DateTime now = tm().getTransactionTime();
    checkLinkedDomains(targetId, now, ContactResource.class, DomainBase::getReferencedContacts);
    ContactResource existingContact = loadAndVerifyExistence(ContactResource.class, targetId, now);
    verifyNoDisallowedStatuses(existingContact, DISALLOWED_STATUSES);
    verifyOptionalAuthInfo(authInfo, existingContact);
    if (!isSuperuser) {
      verifyResourceOwnership(registrarId, existingContact);
    }
    Type historyEntryType;
    Code resultCode;
    ContactResource newContact;
    if (tm().isOfy()) {
      asyncTaskEnqueuer.enqueueAsyncDelete(
          existingContact, tm().getTransactionTime(), registrarId, trid, isSuperuser);
      newContact = existingContact.asBuilder().addStatusValue(StatusValue.PENDING_DELETE).build();
      historyEntryType = Type.CONTACT_PENDING_DELETE;
      resultCode = SUCCESS_WITH_ACTION_PENDING;
    } else {
      // Handle pending transfers on contact deletion.
      newContact =
          existingContact.getStatusValues().contains(StatusValue.PENDING_TRANSFER)
              ? denyPendingTransfer(existingContact, SERVER_CANCELLED, now, registrarId)
              : existingContact;
      // Wipe out PII on contact deletion.
      newContact =
          newContact.asBuilder().wipeOut().setStatusValues(null).setDeletionTime(now).build();
      historyEntryType = Type.CONTACT_DELETE;
      resultCode = SUCCESS;
    }
    ContactHistory contactHistory =
        historyBuilder.setType(historyEntryType).setContact(newContact).build();
    if (!tm().isOfy()) {
      handlePendingTransferOnDelete(existingContact, newContact, now, contactHistory);
    }
    tm().insert(contactHistory);
    tm().update(newContact);
    return responseBuilder.setResultFromCode(resultCode).build();
  }
}
