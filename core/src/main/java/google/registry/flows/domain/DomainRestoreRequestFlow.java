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

import static google.registry.dns.DnsUtils.requestDomainDnsRefresh;
import static google.registry.flows.FlowUtils.createHistoryEntryId;
import static google.registry.flows.FlowUtils.validateRegistrarIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyOptionalAuthInfo;
import static google.registry.flows.ResourceFlowUtils.verifyResourceOwnership;
import static google.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static google.registry.flows.domain.DomainFlowUtils.checkHasBillingAccount;
import static google.registry.flows.domain.DomainFlowUtils.newAutorenewBillingEvent;
import static google.registry.flows.domain.DomainFlowUtils.newAutorenewPollMessage;
import static google.registry.flows.domain.DomainFlowUtils.validateFeeChallenge;
import static google.registry.flows.domain.DomainFlowUtils.verifyNotReserved;
import static google.registry.flows.domain.DomainFlowUtils.verifyPremiumNameIsNotBlocked;
import static google.registry.flows.domain.DomainFlowUtils.verifyRegistrarIsActive;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_RESTORE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InternetDomainName;
import google.registry.flows.EppException;
import google.registry.flows.EppException.CommandUseErrorException;
import google.registry.flows.EppException.StatusProhibitsOperationException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.MutatingFlow;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainCommand.Update;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.fee.BaseFee.FeeType;
import google.registry.model.domain.fee.Fee;
import google.registry.model.domain.fee.FeeTransformResponseExtension;
import google.registry.model.domain.fee.FeeUpdateCommandExtension;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.rgp.RgpUpdateExtension;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import google.registry.model.tld.Tld;
import jakarta.inject.Inject;
import java.util.Optional;
import org.joda.money.Money;
import org.joda.time.DateTime;

/**
 * An EPP flow that requests that a domain in the redemption grace period be restored.
 *
 * <p>When a domain is deleted it is removed from DNS immediately and marked as pending delete, but
 * is not actually soft deleted. There is a period (by default 30 days) during which it can be
 * restored by the original owner. When that period expires there is a second period (by default 5
 * days) during which the domain cannot be restored. After that period anyone can re-register this
 * name.
 *
 * <p>This flow is called a restore "request" because technically it is only supposed to signal that
 * the registrar requests the restore, which the registry can choose to process or not based on a
 * restore report that is submitted through an out-of-band process and details the request. However,
 * in practice this flow does the restore immediately. This is allowable because all the fields on a
 * restore report are optional or have default values, and so by policy when the request comes in we
 * consider it to have been accompanied by a default-initialized report which we auto-approve.
 *
 * <p>Restores cost a fixed restore fee plus a one-year renewal fee for the domain. The domain is
 * restored to a single year expiration starting at the restore time, regardless of what the
 * original expiration time was.
 *
 * @error {@link google.registry.flows.EppException.UnimplementedExtensionException}
 * @error {@link google.registry.flows.FlowUtils.NotLoggedInException}
 * @error {@link google.registry.flows.FlowUtils.UnknownCurrencyEppException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException}
 * @error {@link DomainFlowUtils.CurrencyUnitMismatchException}
 * @error {@link DomainFlowUtils.CurrencyValueScaleException}
 * @error {@link DomainFlowUtils.DomainReservedException}
 * @error {@link DomainFlowUtils.FeesMismatchException}
 * @error {@link DomainFlowUtils.FeesRequiredForPremiumNameException}
 * @error {@link DomainFlowUtils.MissingBillingAccountMapException}
 * @error {@link DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link DomainFlowUtils.PremiumNameBlockedException}
 * @error {@link DomainFlowUtils.RegistrarMustBeActiveForThisOperationException}
 * @error {@link DomainFlowUtils.UnsupportedFeeAttributeException}
 * @error {@link DomainRestoreRequestFlow.DomainNotEligibleForRestoreException}
 * @error {@link DomainRestoreRequestFlow.RestoreCommandIncludesChangesException}
 */
@ReportingSpec(ActivityReportField.DOMAIN_RGP_RESTORE_REQUEST)
public final class DomainRestoreRequestFlow implements MutatingFlow {

  @Inject ResourceCommand resourceCommand;
  @Inject ExtensionManager extensionManager;
  @Inject EppInput eppInput;
  @Inject Optional<AuthInfo> authInfo;
  @Inject @RegistrarId String registrarId;
  @Inject @TargetId String targetId;
  @Inject @Superuser boolean isSuperuser;
  @Inject DomainHistory.Builder historyBuilder;
  @Inject EppResponse.Builder responseBuilder;
  @Inject DomainPricingLogic pricingLogic;
  @Inject DomainRestoreRequestFlow() {}

  @Override
  public EppResponse run() throws EppException {
    extensionManager.register(
        FeeUpdateCommandExtension.class,
        MetadataExtension.class,
        RgpUpdateExtension.class);
    validateRegistrarIsLoggedIn(registrarId);
    verifyRegistrarIsActive(registrarId);
    extensionManager.validate();
    Update command = (Update) resourceCommand;
    DateTime now = tm().getTransactionTime();
    Domain existingDomain = loadAndVerifyExistence(Domain.class, targetId, now);
    boolean isExpired = existingDomain.getRegistrationExpirationTime().isBefore(now);
    FeesAndCredits feesAndCredits =
        pricingLogic.getRestorePrice(Tld.get(existingDomain.getTld()), targetId, now, isExpired);
    Optional<FeeUpdateCommandExtension> feeUpdate =
        eppInput.getSingleExtension(FeeUpdateCommandExtension.class);
    verifyRestoreAllowed(command, existingDomain, feeUpdate, feesAndCredits, now);
    HistoryEntryId domainHistoryId = createHistoryEntryId(existingDomain);
    historyBuilder.setRevisionId(domainHistoryId.getRevisionId());
    ImmutableSet.Builder<ImmutableObject> entitiesToInsert = new ImmutableSet.Builder<>();

    DateTime newExpirationTime =
        existingDomain.getRegistrationExpirationTime().plusYears(isExpired ? 1 : 0);
    // Restore the expiration time on the deleted domain, except if that's already passed, then add
    // a year and bill for it immediately, with no grace period.
    if (isExpired) {
      entitiesToInsert.add(
          createRenewBillingEvent(domainHistoryId, feesAndCredits.getRenewCost(), now));
    }
    // Always bill for the restore itself.
    entitiesToInsert.add(
        createRestoreBillingEvent(domainHistoryId, feesAndCredits.getRestoreCost(), now));

    BillingRecurrence autorenewEvent =
        newAutorenewBillingEvent(existingDomain)
            .setEventTime(newExpirationTime)
            .setRecurrenceEndTime(END_OF_TIME)
            .setDomainHistoryId(domainHistoryId)
            .build();
    entitiesToInsert.add(autorenewEvent);
    PollMessage.Autorenew autorenewPollMessage =
        newAutorenewPollMessage(existingDomain)
            .setEventTime(newExpirationTime)
            .setAutorenewEndTime(END_OF_TIME)
            .setDomainHistoryId(domainHistoryId)
            .build();
    entitiesToInsert.add(autorenewPollMessage);
    Domain newDomain =
        performRestore(
            existingDomain,
            newExpirationTime,
            autorenewEvent,
            autorenewPollMessage,
            now,
            registrarId);
    DomainHistory domainHistory = buildDomainHistory(newDomain, now);
    entitiesToInsert.add(domainHistory);
    tm().update(newDomain);
    tm().insertAll(entitiesToInsert.build());
    if (existingDomain.getDeletePollMessage() != null) {
      tm().delete(existingDomain.getDeletePollMessage());
    }
    requestDomainDnsRefresh(existingDomain.getDomainName());
    return responseBuilder
        .setExtensions(createResponseExtensions(feesAndCredits, feeUpdate, isExpired))
        .build();
  }

  private DomainHistory buildDomainHistory(Domain newDomain, DateTime now) {
    return historyBuilder
        .setType(DOMAIN_RESTORE)
        .setDomain(newDomain)
        .setDomainTransactionRecords(
            ImmutableSet.of(
                DomainTransactionRecord.create(
                    newDomain.getTld(), now, TransactionReportField.RESTORED_DOMAINS, 1)))
        .build();
  }

  private void verifyRestoreAllowed(
      Update command,
      Domain existingDomain,
      Optional<FeeUpdateCommandExtension> feeUpdate,
      FeesAndCredits feesAndCredits,
      DateTime now)
      throws EppException {
    verifyOptionalAuthInfo(authInfo, existingDomain);
    if (!isSuperuser) {
      verifyResourceOwnership(registrarId, existingDomain);
      verifyNotReserved(InternetDomainName.from(targetId), false);
      verifyPremiumNameIsNotBlocked(targetId, now, registrarId);
      checkAllowedAccessToTld(registrarId, existingDomain.getTld());
      checkHasBillingAccount(registrarId, existingDomain.getTld());
    }
    // No other changes can be specified on a restore request.
    if (!command.noChangesPresent()) {
      throw new RestoreCommandIncludesChangesException();
    }
    // Domain must be within the redemptionPeriod to be eligible for restore.
    if (!existingDomain.getGracePeriodStatuses().contains(GracePeriodStatus.REDEMPTION)) {
      throw new DomainNotEligibleForRestoreException();
    }
    validateFeeChallenge(feeUpdate, feesAndCredits, false);
  }

  private static Domain performRestore(
      Domain existingDomain,
      DateTime newExpirationTime,
      BillingRecurrence autorenewEvent,
      PollMessage.Autorenew autorenewPollMessage,
      DateTime now,
      String registrarId) {
    return existingDomain
        .asBuilder()
        .setRegistrationExpirationTime(newExpirationTime)
        .setDeletionTime(END_OF_TIME)
        .setStatusValues(null)
        .setGracePeriods(null)
        .setDeletePollMessage(null)
        .setAutorenewBillingEvent(autorenewEvent.createVKey())
        .setAutorenewPollMessage(autorenewPollMessage.createVKey())
        // Clear the autorenew end time so if it had expired but is now explicitly being restored,
        // it won't immediately be deleted again.
        .setAutorenewEndTime(Optional.empty())
        .setLastEppUpdateTime(now)
        .setLastEppUpdateRegistrarId(registrarId)
        .build();
  }

  private BillingEvent createRenewBillingEvent(
      HistoryEntryId domainHistoryId, Money renewCost, DateTime now) {
    return prepareBillingEvent(domainHistoryId, renewCost, now).setReason(Reason.RENEW).build();
  }

  private BillingEvent createRestoreBillingEvent(
      HistoryEntryId domainHistoryId, Money restoreCost, DateTime now) {
    return prepareBillingEvent(domainHistoryId, restoreCost, now).setReason(Reason.RESTORE).build();
  }

  private BillingEvent.Builder prepareBillingEvent(
      HistoryEntryId domainHistoryId, Money cost, DateTime now) {
    return new BillingEvent.Builder()
        .setTargetId(targetId)
        .setRegistrarId(registrarId)
        .setEventTime(now)
        .setBillingTime(now)
        .setPeriodYears(1)
        .setCost(cost)
        .setDomainHistoryId(domainHistoryId);
  }

  private static ImmutableList<FeeTransformResponseExtension> createResponseExtensions(
      FeesAndCredits feesAndCredits,
      Optional<FeeUpdateCommandExtension> feeUpdate,
      boolean isExpired) {
    ImmutableList.Builder<Fee> fees = new ImmutableList.Builder<>();
    fees.add(
        Fee.create(
            feesAndCredits.getRestoreCost().getAmount(),
            FeeType.RESTORE,
            feesAndCredits.hasPremiumFeesOfType(FeeType.RESTORE)));
    if (isExpired) {
      fees.add(
          Fee.create(
              feesAndCredits.getRenewCost().getAmount(),
              FeeType.RENEW,
              feesAndCredits.hasPremiumFeesOfType(FeeType.RENEW)));
    }
    return feeUpdate
        .map(
            feeUpdateCommandExtension ->
                ImmutableList.of(
                    feeUpdateCommandExtension
                        .createResponseBuilder()
                        .setCurrency(feesAndCredits.getCurrency())
                        .setFees(fees.build())
                        .build()))
        .orElseGet(ImmutableList::of);
  }

  /** Restore command cannot have other changes specified. */
  static class RestoreCommandIncludesChangesException extends CommandUseErrorException {
    RestoreCommandIncludesChangesException() {
      super("Restore command cannot have other changes specified");
    }
  }

  /** Domain is not eligible for restore. */
  static class DomainNotEligibleForRestoreException extends StatusProhibitsOperationException {
    DomainNotEligibleForRestoreException() {
      super("Domain is not eligible for restore");
    }
  }
}
