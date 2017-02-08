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

package google.registry.flows.domain;

import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Iterables.getOnlyElement;
import static google.registry.flows.FlowUtils.validateClientIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.approvePendingTransfer;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyHasPendingTransfer;
import static google.registry.flows.ResourceFlowUtils.verifyOptionalAuthInfo;
import static google.registry.flows.ResourceFlowUtils.verifyResourceOwnership;
import static google.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static google.registry.flows.domain.DomainFlowUtils.updateAutorenewRecurrenceEndTime;
import static google.registry.flows.domain.DomainTransferUtils.createGainingTransferPollMessage;
import static google.registry.flows.domain.DomainTransferUtils.createTransferResponse;
import static google.registry.model.domain.DomainResource.extendRegistrationWithCap;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.pricing.PricingEngineProxy.getDomainRenewCost;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.ClientId;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.TransactionalFlow;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * An EPP flow that approves a pending transfer on a domain.
 *
 * <p>The "gaining" registrar requests a transfer from the "losing" (aka current) registrar. The
 * losing registrar has a "transfer" time period to respond (by default five days) after which the
 * transfer is automatically approved. Within that window, this flow allows the losing client to
 * explicitly approve the transfer request, which then becomes effective immediately.
 *
 * <p>When the transfer was requested, poll messages and billing events were saved to Datastore with
 * timestamps such that they only would become active when the transfer period passed. In this flow,
 * those speculative objects are deleted and replaced with new ones with the correct approval time.
 *
 * @error {@link google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException}
 * @error {@link google.registry.flows.exceptions.NotPendingTransferException}
 * @error {@link DomainFlowUtils.NotAuthorizedForTldException}
 */
public final class DomainTransferApproveFlow implements TransactionalFlow {

  @Inject ExtensionManager extensionManager;
  @Inject Optional<AuthInfo> authInfo;
  @Inject @ClientId String clientId;
  @Inject @TargetId String targetId;
  @Inject HistoryEntry.Builder historyBuilder;
  @Inject EppResponse.Builder responseBuilder;
  @Inject DomainTransferApproveFlow() {}

  /**
   * <p>The logic in this flow, which handles client approvals, very closely parallels the logic in
   * {@link DomainResource#cloneProjectedAtTime} which handles implicit server approvals.
   */
  @Override
  public final EppResponse run() throws EppException {
    extensionManager.register(MetadataExtension.class);
    extensionManager.validate();
    validateClientIsLoggedIn(clientId);
    DateTime now = ofy().getTransactionTime();
    DomainResource existingDomain = loadAndVerifyExistence(DomainResource.class, targetId, now);
    verifyOptionalAuthInfo(authInfo, existingDomain);
    verifyHasPendingTransfer(existingDomain);
    verifyResourceOwnership(clientId, existingDomain);
    String tld = existingDomain.getTld();
    checkAllowedAccessToTld(clientId, tld);
    TransferData transferData = existingDomain.getTransferData();
    String gainingClientId = transferData.getGainingClientId();
    HistoryEntry historyEntry = historyBuilder
        .setType(HistoryEntry.Type.DOMAIN_TRANSFER_APPROVE)
        .setModificationTime(now)
        .setOtherClientId(gainingClientId)
        .setParent(Key.create(existingDomain))
        .build();
    int extraYears = transferData.getExtendedRegistrationYears();
    // Bill for the transfer.
    BillingEvent.OneTime billingEvent = new BillingEvent.OneTime.Builder()
        .setReason(Reason.TRANSFER)
        .setTargetId(targetId)
        .setClientId(gainingClientId)
        .setPeriodYears(extraYears)
        .setCost(getDomainRenewCost(targetId, transferData.getTransferRequestTime(), extraYears))
        .setEventTime(now)
        .setBillingTime(now.plus(Registry.get(tld).getTransferGracePeriodLength()))
        .setParent(historyEntry)
        .build();
    // If we are within an autorenew grace period, cancel the autorenew billing event and reduce
    // the number of years to extend the registration by one.
    GracePeriod autorenewGrace = getOnlyElement(
        filter(
            existingDomain.getGracePeriods(),
            new Predicate<GracePeriod>() {
              @Override
              public boolean apply(GracePeriod gracePeriod) {
                return GracePeriodStatus.AUTO_RENEW.equals(gracePeriod.getType());
              }}),
        null);
    if (autorenewGrace != null) {
      extraYears--;
      ofy().save().entity(
          BillingEvent.Cancellation.forGracePeriod(autorenewGrace, historyEntry, targetId));
    }
    // Close the old autorenew event and poll message at the transfer time (aka now). This may end
    // up deleting the poll message.
    updateAutorenewRecurrenceEndTime(existingDomain, now);
    DateTime newExpirationTime = extendRegistrationWithCap(
        now, existingDomain.getRegistrationExpirationTime(), extraYears);
    // Create a new autorenew event starting at the expiration time.
    BillingEvent.Recurring autorenewEvent = new BillingEvent.Recurring.Builder()
        .setReason(Reason.RENEW)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setTargetId(targetId)
        .setClientId(gainingClientId)
        .setEventTime(newExpirationTime)
        .setRecurrenceEndTime(END_OF_TIME)
        .setParent(historyEntry)
        .build();
    // Create a new autorenew poll message.
    PollMessage.Autorenew gainingClientAutorenewPollMessage = new PollMessage.Autorenew.Builder()
        .setTargetId(targetId)
        .setClientId(gainingClientId)
        .setEventTime(newExpirationTime)
        .setAutorenewEndTime(END_OF_TIME)
        .setMsg("Domain was auto-renewed.")
        .setParent(historyEntry)
        .build();
    DomainResource newDomain =
        approvePendingTransfer(existingDomain, TransferStatus.CLIENT_APPROVED, now)
            .asBuilder()
            .setRegistrationExpirationTime(newExpirationTime)
            .setAutorenewBillingEvent(Key.create(autorenewEvent))
            .setAutorenewPollMessage(Key.create(gainingClientAutorenewPollMessage))
            // Remove all the old grace periods and add a new one for the transfer.
            .setGracePeriods(ImmutableSet.of(
                GracePeriod.forBillingEvent(GracePeriodStatus.TRANSFER, billingEvent)))
            .build();
    // Create a poll message for the gaining client.
    PollMessage gainingClientPollMessage = createGainingTransferPollMessage(
        targetId,
        newDomain.getTransferData(),
        newExpirationTime,
        historyEntry);
    ofy().save().<ImmutableObject>entities(
        newDomain,
        historyEntry,
        billingEvent,
        autorenewEvent,
        gainingClientPollMessage,
        gainingClientAutorenewPollMessage);
    // Delete the billing event and poll messages that were written in case the transfer would have
    // been implicitly server approved.
    ofy().delete().keys(existingDomain.getTransferData().getServerApproveEntities());
    return responseBuilder
        .setResData(createTransferResponse(
            targetId, newDomain.getTransferData(), newDomain.getRegistrationExpirationTime()))
        .build();
  }
}
