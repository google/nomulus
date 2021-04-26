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

import static com.google.common.collect.Sets.union;
import static google.registry.flows.FlowUtils.validateClientIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.checkSameValuesNotAddedAndRemoved;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyAllStatusesAreClientSettable;
import static google.registry.flows.ResourceFlowUtils.verifyNoDisallowedStatuses;
import static google.registry.flows.ResourceFlowUtils.verifyOptionalAuthInfo;
import static google.registry.flows.ResourceFlowUtils.verifyResourceOwnership;
import static google.registry.flows.contact.ContactFlowUtils.validateAsciiPostalInfo;
import static google.registry.flows.contact.ContactFlowUtils.validateContactAgainstPolicy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableSet;
import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.ClientId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.TransactionalFlow;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.flows.exceptions.ResourceHasClientUpdateProhibitedException;
import google.registry.model.contact.ContactCommand.Update;
import google.registry.model.contact.ContactCommand.Update.Change;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactResource;
import google.registry.model.contact.PostalInfo;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * An EPP flow that updates a contact.
 *
 * @error {@link google.registry.flows.ResourceFlowUtils.AddRemoveSameValueException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException}
 * @error {@link google.registry.flows.ResourceFlowUtils.StatusNotClientSettableException}
 * @error {@link google.registry.flows.exceptions.ResourceHasClientUpdateProhibitedException}
 * @error {@link google.registry.flows.exceptions.ResourceStatusProhibitsOperationException}
 * @error {@link ContactFlowUtils.BadInternationalizedPostalInfoException}
 * @error {@link ContactFlowUtils.DeclineContactDisclosureFieldDisallowedPolicyException}
 */
@ReportingSpec(ActivityReportField.CONTACT_UPDATE)
public final class ContactUpdateFlow implements TransactionalFlow {

  /**
   * Note that CLIENT_UPDATE_PROHIBITED is intentionally not in this list. This is because it
   * requires special checking, since you must be able to clear the status off the object with an
   * update.
   */
  private static final ImmutableSet<StatusValue> DISALLOWED_STATUSES = ImmutableSet.of(
      StatusValue.PENDING_DELETE,
      StatusValue.SERVER_UPDATE_PROHIBITED);

  @Inject ResourceCommand resourceCommand;
  @Inject ExtensionManager extensionManager;
  @Inject Optional<AuthInfo> authInfo;
  @Inject @ClientId String clientId;
  @Inject @TargetId String targetId;
  @Inject @Superuser boolean isSuperuser;
  @Inject ContactHistory.Builder historyBuilder;
  @Inject EppResponse.Builder responseBuilder;
  @Inject ContactUpdateFlow() {}

  @Override
  public final EppResponse run() throws EppException {
    extensionManager.register(MetadataExtension.class);
    extensionManager.validate();
    validateClientIsLoggedIn(clientId);
    Update command = (Update) resourceCommand;
    DateTime now = tm().getTransactionTime();
    ContactResource existingContact = loadAndVerifyExistence(ContactResource.class, targetId, now);
    verifyOptionalAuthInfo(authInfo, existingContact);
    ImmutableSet<StatusValue> statusToRemove = command.getInnerRemove().getStatusValues();
    ImmutableSet<StatusValue> statusesToAdd = command.getInnerAdd().getStatusValues();
    if (!isSuperuser) {  // The superuser can update any contact and set any status.
      verifyResourceOwnership(clientId, existingContact);
      verifyAllStatusesAreClientSettable(union(statusesToAdd, statusToRemove));
    }
    verifyNoDisallowedStatuses(existingContact, DISALLOWED_STATUSES);
    checkSameValuesNotAddedAndRemoved(statusesToAdd, statusToRemove);
    ContactResource.Builder builder = existingContact.asBuilder();
    Change change = command.getInnerChange();
    // The spec requires the following behaviors:
    //   * If you update part of a postal info, the fields that you didn't update are unchanged.
    //   * If you update one postal info but not the other, the other is deleted.
    // Therefore, if you want to preserve one postal info and update another you need to send the
    // update and also something that technically updates the preserved one, even if it only
    // "updates" it by setting just one field to the same value.
    PostalInfo internationalized = change.getInternationalizedPostalInfo();
    PostalInfo localized = change.getLocalizedPostalInfo();
    if (internationalized != null) {
      builder.overlayInternationalizedPostalInfo(internationalized);
      if (localized == null) {
        builder.setLocalizedPostalInfo(null);
      }
    }
    if (localized != null) {
      builder.overlayLocalizedPostalInfo(localized);
      if (internationalized == null) {
        builder.setInternationalizedPostalInfo(null);
      }
    }
    ContactResource newContact = builder
        .setLastEppUpdateTime(now)
        .setLastEppUpdateClientId(clientId)
        .setAuthInfo(preferFirst(change.getAuthInfo(), existingContact.getAuthInfo()))
        .setDisclose(preferFirst(change.getDisclose(), existingContact.getDisclose()))
        .setEmailAddress(preferFirst(change.getEmail(), existingContact.getEmailAddress()))
        .setFaxNumber(preferFirst(change.getFax(), existingContact.getFaxNumber()))
        .setVoiceNumber(preferFirst(change.getVoice(), existingContact.getVoiceNumber()))
        .addStatusValues(statusesToAdd)
        .removeStatusValues(statusToRemove)
        .build();
    // If the resource is marked with clientUpdateProhibited, and this update did not clear that
    // status, then the update must be disallowed (unless a superuser is requesting the change).
    if (!isSuperuser
        && existingContact.getStatusValues().contains(StatusValue.CLIENT_UPDATE_PROHIBITED)
        && newContact.getStatusValues().contains(StatusValue.CLIENT_UPDATE_PROHIBITED)) {
      throw new ResourceHasClientUpdateProhibitedException();
    }
    validateAsciiPostalInfo(newContact.getInternationalizedPostalInfo());
    validateContactAgainstPolicy(newContact);
    historyBuilder
        .setType(HistoryEntry.Type.CONTACT_UPDATE)
        .setModificationTime(now)
        .setXmlBytes(null) // We don't want to store contact details in the history entry.
        .setContactBase(newContact);
    tm().insert(historyBuilder.build());
    tm().update(newContact);
    return responseBuilder.build();
  }

  /** Return the first non-null param, or null if both are null. */
  @Nullable
  private static <T> T preferFirst(@Nullable T a, @Nullable T b) {
    return a != null ? a : b;
  }
}
