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

import static com.google.common.collect.Iterables.getOnlyElement;
import static google.registry.flows.FlowUtils.createHistoryEntryId;
import static google.registry.flows.FlowUtils.validateRegistrarIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.computeExDateForApprovalTime;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyHasPendingTransfer;
import static google.registry.flows.ResourceFlowUtils.verifyOptionalAuthInfo;
import static google.registry.flows.ResourceFlowUtils.verifyResourceOwnership;
import static google.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static google.registry.flows.domain.DomainFlowUtils.createCancelingRecords;
import static google.registry.flows.domain.DomainFlowUtils.updateAutorenewRecurrenceEndTime;
import static google.registry.flows.domain.DomainTransferUtils.createGainingTransferPollMessage;
import static google.registry.flows.domain.DomainTransferUtils.createTransferResponse;
import static google.registry.model.ResourceTransferUtils.approvePendingTransfer;
import static google.registry.model.reporting.DomainTransactionRecord.TransactionReportField.TRANSFER_SUCCESSFUL;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_TRANSFER_APPROVE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.CollectionUtils.union;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.MutatingFlow;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.flows.domain.token.AllocationTokenFlowUtils;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingBase.Flag;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingBase.RenewalPriceBehavior;
import google.registry.model.billing.BillingCancellation;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.token.AllocationTokenExtension;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import google.registry.model.tld.Tld;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferStatus;
import jakarta.inject.Inject;
import java.util.Optional;
import org.joda.money.Money;
import org.joda.time.DateTime;

/**
 * An EPP flow that approves a pending transfer on a domain.
 *
 * <p>The "gaining" registrar requests a transfer from the "losing" (aka current) registrar. The
 * losing registrar has a "transfer" time period to respond (by default five days) after which the
 * transfer is automatically approved. Within that window, this flow allows the losing client to
 * explicitly approve the transfer request, which then becomes effective immediately.
 *
 * <p>When the transfer was requested, poll messages and billing events were saved to the database
 * with timestamps such that they only would become active when the transfer period passed. In this
 * flow, those speculative objects are deleted and replaced with new ones with the correct approval
 * time.
 *
 * @error {@link google.registry.flows.FlowUtils.NotLoggedInException}
 * @error {@link google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException}
 * @error {@link google.registry.flows.exceptions.NotPendingTransferException}
 * @error {@link DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link
 *     google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForDomainException}
 * @error {@link AllocationTokenFlowUtils.NonexistentAllocationTokenException}
 * @error {@link
 *     google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotInPromotionException}
 * @error {@link
 *     google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForRegistrarException}
 * @error {@link
 *     google.registry.flows.domain.token.AllocationTokenFlowUtils.AlreadyRedeemedAllocationTokenException}
 */
@ReportingSpec(ActivityReportField.DOMAIN_TRANSFER_APPROVE)
public final class DomainTransferApproveFlow implements MutatingFlow {

  @Inject ExtensionManager extensionManager;
  @Inject Optional<AuthInfo> authInfo;
  @Inject @RegistrarId String registrarId;
  @Inject @TargetId String targetId;
  @Inject @Superuser boolean isSuperuser;
  @Inject DomainHistory.Builder historyBuilder;
  @Inject EppResponse.Builder responseBuilder;
  @Inject DomainPricingLogic pricingLogic;
  @Inject EppInput eppInput;

  @Inject DomainTransferApproveFlow() {}

  /**
   * The logic in this flow, which handles client approvals, very closely parallels the logic in
   * {@link Domain#cloneProjectedAtTime} which handles implicit server approvals.
   */
  @Override
  public EppResponse run() throws EppException {
    extensionManager.register(MetadataExtension.class, AllocationTokenExtension.class);
    validateRegistrarIsLoggedIn(registrarId);
    extensionManager.validate();
    DateTime now = tm().getTransactionTime();
    Domain existingDomain = loadAndVerifyExistence(Domain.class, targetId, now);
    AllocationTokenFlowUtils.loadAllocationTokenFromExtension(
        registrarId, targetId, now, eppInput.getSingleExtension(AllocationTokenExtension.class));
    verifyOptionalAuthInfo(authInfo, existingDomain);
    verifyHasPendingTransfer(existingDomain);
    verifyResourceOwnership(registrarId, existingDomain);
    String tldStr = existingDomain.getTld();
    if (!isSuperuser) {
      checkAllowedAccessToTld(registrarId, tldStr);
    }
    DomainTransferData transferData = existingDomain.getTransferData();
    String gainingRegistrarId = transferData.getGainingRegistrarId();
    // Create a transfer billing event for 1 year, unless the superuser extension was used to set
    // the transfer period to zero. There is not a transfer cost if the transfer period is zero.
    BillingRecurrence existingBillingRecurrence =
        tm().loadByKey(existingDomain.getAutorenewBillingEvent());
    HistoryEntryId domainHistoryId = createHistoryEntryId(existingDomain);
    historyBuilder.setRevisionId(domainHistoryId.getRevisionId());
    boolean hasBulkToken = existingDomain.getCurrentBulkToken().isPresent();
    Money renewalPrice =
        hasBulkToken ? null : existingBillingRecurrence.getRenewalPrice().orElse(null);
    Optional<BillingEvent> billingEvent =
        transferData.getTransferPeriod().getValue() == 0
            ? Optional.empty()
            : Optional.of(
                new BillingEvent.Builder()
                    .setReason(Reason.TRANSFER)
                    .setTargetId(targetId)
                    .setRegistrarId(gainingRegistrarId)
                    .setPeriodYears(1)
                    .setCost(
                        pricingLogic
                            .getTransferPrice(
                                Tld.get(tldStr),
                                targetId,
                                transferData.getTransferRequestTime(),
                                // When removing a domain from bulk pricing it should return to the
                                // default recurrence billing behavior so the existing recurrence
                                // billing event should not be passed in.
                                hasBulkToken ? null : existingBillingRecurrence)
                            .getRenewCost())
                    .setEventTime(now)
                    .setBillingTime(now.plus(Tld.get(tldStr).getTransferGracePeriodLength()))
                    .setDomainHistoryId(domainHistoryId)
                    .build());

    ImmutableList.Builder<ImmutableObject> entitiesToInsert = new ImmutableList.Builder<>();
    // If we are within an autorenew grace period, cancel the autorenew billing event and don't
    // increase the registration time, since the transfer subsumes the autorenew's extra year.
    GracePeriod autorenewGrace =
        getOnlyElement(existingDomain.getGracePeriodsOfType(GracePeriodStatus.AUTO_RENEW), null);
    if (autorenewGrace != null) {
      // During a normal transfer, if the domain is in the auto-renew grace period, the auto-renew
      // billing event is cancelled and the gaining registrar is charged for the one-year renewal.
      // But, if the superuser extension is used to request a transfer without an additional year
      // then the gaining registrar is not charged for the one-year renewal and the losing registrar
      // still needs to be charged for the auto-renew.
      if (billingEvent.isPresent()) {
        entitiesToInsert.add(
            BillingCancellation.forGracePeriod(autorenewGrace, now, domainHistoryId, targetId));
      }
    }
    // Close the old autorenew event and poll message at the transfer time (aka now). This may end
    // up deleting the poll message.
    updateAutorenewRecurrenceEndTime(
        existingDomain, existingBillingRecurrence, now, domainHistoryId);
    DateTime newExpirationTime =
        computeExDateForApprovalTime(existingDomain, now, transferData.getTransferPeriod());
    // Create a new autorenew event starting at the expiration time.
    BillingRecurrence autorenewEvent =
        new BillingRecurrence.Builder()
            .setReason(Reason.RENEW)
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setTargetId(targetId)
            .setRegistrarId(gainingRegistrarId)
            .setEventTime(newExpirationTime)
            .setRenewalPriceBehavior(
                hasBulkToken
                    ? RenewalPriceBehavior.DEFAULT
                    : existingBillingRecurrence.getRenewalPriceBehavior())
            .setRenewalPrice(renewalPrice)
            .setRecurrenceEndTime(END_OF_TIME)
            .setDomainHistoryId(domainHistoryId)
            .build();
    // Create a new autorenew poll message.
    PollMessage.Autorenew gainingClientAutorenewPollMessage =
        new PollMessage.Autorenew.Builder()
            .setTargetId(targetId)
            .setRegistrarId(gainingRegistrarId)
            .setEventTime(newExpirationTime)
            .setAutorenewEndTime(END_OF_TIME)
            .setMsg("Domain was auto-renewed.")
            .setDomainHistoryId(domainHistoryId)
            .build();
    // Construct the post-transfer domain.
    Domain partiallyApprovedDomain =
        approvePendingTransfer(existingDomain, TransferStatus.CLIENT_APPROVED, now);
    Domain newDomain =
        partiallyApprovedDomain
            .asBuilder()
            // Update the transferredRegistrationExpirationTime here since approvePendingTransfer()
            // doesn't know what to set it to and leaves it null.
            .setTransferData(
                partiallyApprovedDomain
                    .getTransferData()
                    .asBuilder()
                    .setTransferredRegistrationExpirationTime(newExpirationTime)
                    .build())
            .setRegistrationExpirationTime(newExpirationTime)
            .setAutorenewBillingEvent(autorenewEvent.createVKey())
            .setAutorenewPollMessage(gainingClientAutorenewPollMessage.createVKey())
            // Remove all the old grace periods and add a new one for the transfer.
            .setGracePeriods(
                billingEvent
                    .map(
                        event ->
                            ImmutableSet.of(
                                GracePeriod.forBillingEvent(
                                    GracePeriodStatus.TRANSFER, existingDomain.getRepoId(), event)))
                    .orElseGet(ImmutableSet::of))
            .setLastEppUpdateTime(now)
            .setLastEppUpdateRegistrarId(registrarId)
            // Even if the existing domain had a bulk token, that bulk token should be removed
            // on transfer
            .setCurrentBulkToken(null)
            .build();

    Tld tld = Tld.get(existingDomain.getTld());
    DomainHistory domainHistory = buildDomainHistory(newDomain, tld, now, gainingRegistrarId);
    // Create a poll message for the gaining client.
    PollMessage gainingClientPollMessage =
        createGainingTransferPollMessage(
            targetId, newDomain.getTransferData(), newExpirationTime, now, domainHistoryId);
    billingEvent.ifPresent(entitiesToInsert::add);
    entitiesToInsert.add(
        autorenewEvent, gainingClientPollMessage, gainingClientAutorenewPollMessage, domainHistory);
    tm().update(newDomain);
    tm().insertAll(entitiesToInsert.build());
    // Delete the billing event and poll messages that were written in case the transfer would have
    // been implicitly server approved.
    tm().delete(existingDomain.getTransferData().getServerApproveEntities());
    return responseBuilder
        .setResData(createTransferResponse(
            targetId, newDomain.getTransferData(), newDomain.getRegistrationExpirationTime()))
        .build();
  }

  private DomainHistory buildDomainHistory(
      Domain newDomain, Tld tld, DateTime now, String gainingRegistrarId) {
    ImmutableSet<DomainTransactionRecord> cancelingRecords =
        createCancelingRecords(
            newDomain,
            now,
            tld.getAutomaticTransferLength().plus(tld.getTransferGracePeriodLength()),
            ImmutableSet.of(TRANSFER_SUCCESSFUL));
    return historyBuilder
        .setType(DOMAIN_TRANSFER_APPROVE)
        .setOtherRegistrarId(gainingRegistrarId)
        .setDomain(newDomain)
        .setDomainTransactionRecords(
            union(
                cancelingRecords,
                DomainTransactionRecord.create(
                    newDomain.getTld(),
                    now.plus(tld.getTransferGracePeriodLength()),
                    TRANSFER_SUCCESSFUL,
                    1)))
        .build();
  }
}
