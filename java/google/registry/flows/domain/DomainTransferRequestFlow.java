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

import static google.registry.flows.FlowUtils.validateClientIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyAuthInfo;
import static google.registry.flows.ResourceFlowUtils.verifyAuthInfoPresentForResourceTransfer;
import static google.registry.flows.ResourceFlowUtils.verifyNoDisallowedStatuses;
import static google.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static google.registry.flows.domain.DomainFlowUtils.updateAutorenewRecurrenceEndTime;
import static google.registry.flows.domain.DomainFlowUtils.validateFeeChallenge;
import static google.registry.flows.domain.DomainFlowUtils.verifyPremiumNameIsNotBlocked;
import static google.registry.flows.domain.DomainFlowUtils.verifyUnitIsYears;
import static google.registry.flows.domain.DomainTransferUtils.createLosingTransferPollMessage;
import static google.registry.flows.domain.DomainTransferUtils.createPendingTransferData;
import static google.registry.flows.domain.DomainTransferUtils.createTransferResponse;
import static google.registry.flows.domain.DomainTransferUtils.createTransferServerApproveEntities;
import static google.registry.model.domain.DomainResource.extendRegistrationWithCap;
import static google.registry.model.eppoutput.Result.Code.SUCCESS_WITH_ACTION_PENDING;
import static google.registry.model.ofy.ObjectifyService.ofy;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.ClientId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.TransactionalFlow;
import google.registry.flows.exceptions.AlreadyPendingTransferException;
import google.registry.flows.exceptions.ObjectAlreadySponsoredException;
import google.registry.model.domain.DomainCommand.Transfer;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.Period;
import google.registry.model.domain.fee.FeeTransferCommandExtension;
import google.registry.model.domain.fee.FeeTransformResponseExtension;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferData.Builder;
import google.registry.model.transfer.TransferData.TransferServerApproveEntity;
import google.registry.model.transfer.TransferResponse.DomainTransferResponse;
import google.registry.model.transfer.TransferStatus;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * An EPP flow that requests a transfer on a domain.
 *
 * <p>The "gaining" registrar requests a transfer from the "losing" (aka current) registrar. The
 * losing registrar has a "transfer" time period to respond (by default five days) after which the
 * transfer is automatically approved. Within that window, the transfer might be approved explicitly
 * by the losing registrar or rejected, and the gaining registrar can also cancel the transfer
 * request.
 *
 * <p>When a transfer is requested, poll messages and billing events are saved to Datastore with
 * timestamps such that they only become active when the server-approval period passes. Keys to
 * these speculative objects are saved in the domain's transfer data, and on explicit approval,
 * rejection or cancellation of the request, they will be deleted (and in the approval case,
 * replaced with new ones with the correct approval time).
 *
 * @error {@link google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link google.registry.flows.exceptions.AlreadyPendingTransferException}
 * @error {@link google.registry.flows.exceptions.MissingTransferRequestAuthInfoException}
 * @error {@link google.registry.flows.exceptions.ObjectAlreadySponsoredException}
 * @error {@link google.registry.flows.exceptions.ResourceStatusProhibitsOperationException}
 * @error {@link DomainFlowUtils.BadPeriodUnitException}
 * @error {@link DomainFlowUtils.CurrencyUnitMismatchException}
 * @error {@link DomainFlowUtils.CurrencyValueScaleException}
 * @error {@link DomainFlowUtils.FeesMismatchException}
 * @error {@link DomainFlowUtils.FeesRequiredForPremiumNameException}
 * @error {@link DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link DomainFlowUtils.PremiumNameBlockedException}
 * @error {@link DomainFlowUtils.UnsupportedFeeAttributeException}
 */
public final class DomainTransferRequestFlow implements TransactionalFlow {

  private static final ImmutableSet<StatusValue> DISALLOWED_STATUSES = ImmutableSet.of(
      StatusValue.CLIENT_TRANSFER_PROHIBITED,
      StatusValue.PENDING_DELETE,
      StatusValue.SERVER_TRANSFER_PROHIBITED);

  @Inject ResourceCommand resourceCommand;
  @Inject ExtensionManager extensionManager;
  @Inject EppInput eppInput;
  @Inject Optional<AuthInfo> authInfo;
  @Inject @ClientId String gainingClientId;
  @Inject @TargetId String targetId;
  @Inject @Superuser boolean isSuperuser;
  @Inject HistoryEntry.Builder historyBuilder;
  @Inject Trid trid;
  @Inject EppResponse.Builder responseBuilder;
  @Inject DomainPricingLogic pricingLogic;
  @Inject DomainTransferRequestFlow() {}

  @Override
  public final EppResponse run() throws EppException {
    extensionManager.register(
        FeeTransferCommandExtension.class,
        MetadataExtension.class);
    extensionManager.validate();
    validateClientIsLoggedIn(gainingClientId);
    Period period = ((Transfer) resourceCommand).getPeriod();
    int years = period.getValue();
    DateTime now = ofy().getTransactionTime();
    DomainResource existingDomain = loadAndVerifyExistence(DomainResource.class, targetId, now);
    verifyTransferAllowed(existingDomain, period, now);
    String tld = existingDomain.getTld();
    Registry registry = Registry.get(tld);
    // An optional extension from the client specifying what they think the transfer should cost.
    FeeTransferCommandExtension feeTransfer =
        eppInput.getSingleExtension(FeeTransferCommandExtension.class);
    FeesAndCredits feesAndCredits = pricingLogic.getTransferPrice(registry, targetId, now, years);
    validateFeeChallenge(targetId, tld, now, feeTransfer, feesAndCredits);
    HistoryEntry historyEntry = buildHistory(period, existingDomain, now);
    DateTime automaticTransferTime = now.plus(registry.getAutomaticTransferLength());
    // The new expiration time if there is a server approval.
    DateTime serverApproveNewExpirationTime = extendRegistrationWithCap(
        automaticTransferTime, existingDomain.getRegistrationExpirationTime(), years);
    // Create speculative entities in anticipation of an automatic server approval.
    ImmutableSet<TransferServerApproveEntity> serverApproveEntities =
        createTransferServerApproveEntities(
            automaticTransferTime,
            serverApproveNewExpirationTime,
            historyEntry,
            existingDomain,
            trid,
            gainingClientId,
            feesAndCredits.getTotalCost(),
            years,
            now);
    // Create the transfer data that represents the pending transfer.
    TransferData pendingTransferData = createPendingTransferData(
        createTransferDataBuilder(existingDomain, automaticTransferTime, years, now),
        serverApproveEntities);
    // Create a poll message to notify the losing registrar that a transfer was requested.
    PollMessage requestPollMessage = createLosingTransferPollMessage(
        targetId, pendingTransferData, serverApproveNewExpirationTime, historyEntry)
            .asBuilder().setEventTime(now).build();
    // End the old autorenew event and poll message at the implicit transfer time. This may delete
    // the poll message if it has no events left. Note that if the automatic transfer succeeds, then
    // cloneProjectedAtTime() will replace these old autorenew entities with the server approve ones
    // that we've created in this flow and stored in pendingTransferData.
    updateAutorenewRecurrenceEndTime(existingDomain, automaticTransferTime);
    DomainResource newDomain = existingDomain.asBuilder()
        .setTransferData(pendingTransferData)
        .addStatusValue(StatusValue.PENDING_TRANSFER)
        .build();
    ofy().save()
        .entities(new ImmutableSet.Builder<>()
            .add(newDomain, historyEntry, requestPollMessage)
            .addAll(serverApproveEntities)
            .build())
        .now();
    return responseBuilder
        .setResultFromCode(SUCCESS_WITH_ACTION_PENDING)
        .setResData(createResponse(period, existingDomain, newDomain, now))
        .setExtensions(createResponseExtensions(feesAndCredits, feeTransfer))
        .build();
  }

  private void verifyTransferAllowed(DomainResource existingDomain, Period period, DateTime now)
      throws EppException {
    verifyNoDisallowedStatuses(existingDomain, DISALLOWED_STATUSES);
    verifyAuthInfoPresentForResourceTransfer(authInfo);
    verifyAuthInfo(authInfo.get(), existingDomain);
    // Verify that the resource does not already have a pending transfer.
    if (TransferStatus.PENDING.equals(existingDomain.getTransferData().getTransferStatus())) {
      throw new AlreadyPendingTransferException(targetId);
    }
    // Verify that this client doesn't already sponsor this resource.
    if (gainingClientId.equals(existingDomain.getCurrentSponsorClientId())) {
      throw new ObjectAlreadySponsoredException();
    }
    checkAllowedAccessToTld(gainingClientId, existingDomain.getTld());
    verifyUnitIsYears(period);
    if (!isSuperuser) {
      verifyPremiumNameIsNotBlocked(targetId, now, gainingClientId);
    }
  }

  private HistoryEntry buildHistory(Period period, DomainResource existingResource, DateTime now) {
    return historyBuilder
        .setType(HistoryEntry.Type.DOMAIN_TRANSFER_REQUEST)
        .setOtherClientId(existingResource.getCurrentSponsorClientId())
        .setPeriod(period)
        .setModificationTime(now)
        .setParent(Key.create(existingResource))
        .build();
  }

  private Builder createTransferDataBuilder(
      DomainResource existingDomain, DateTime automaticTransferTime, int years, DateTime now) {
    return new TransferData.Builder()
        .setTransferRequestTrid(trid)
        .setTransferRequestTime(now)
        .setGainingClientId(gainingClientId)
        .setLosingClientId(existingDomain.getCurrentSponsorClientId())
        .setPendingTransferExpirationTime(automaticTransferTime)
        .setExtendedRegistrationYears(years);
  }

  private DomainTransferResponse createResponse(
      Period period, DomainResource existingDomain, DomainResource newDomain, DateTime now) {
    // If the registration were approved this instant, this is what the new expiration would be,
    // because we cap at 10 years from the moment of approval. This is different than the server
    // approval new expiration time, which is capped at 10 years from the server approve time.
    DateTime approveNowExtendedRegistrationTime = extendRegistrationWithCap(
        now,
        existingDomain.getRegistrationExpirationTime(),
        period.getValue());
    return createTransferResponse(
        targetId, newDomain.getTransferData(), approveNowExtendedRegistrationTime);
  }

  private static ImmutableList<FeeTransformResponseExtension> createResponseExtensions(
      FeesAndCredits feesAndCredits, FeeTransferCommandExtension feeTransfer) {
    return feeTransfer == null
        ? ImmutableList.<FeeTransformResponseExtension>of()
        : ImmutableList.of(feeTransfer.createResponseBuilder()
            .setFees(feesAndCredits.getFees())
            .setCredits(feesAndCredits.getCredits())
            .setCurrency(feesAndCredits.getCurrency())
            .build());
  }
}
