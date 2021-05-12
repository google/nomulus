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

import static google.registry.flows.FlowUtils.createHistoryKey;
import static google.registry.flows.FlowUtils.validateClientIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyOptionalAuthInfo;
import static google.registry.flows.ResourceFlowUtils.verifyResourceOwnership;
import static google.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static google.registry.flows.domain.DomainFlowUtils.newAutorenewBillingEvent;
import static google.registry.flows.domain.DomainFlowUtils.newAutorenewPollMessage;
import static google.registry.flows.domain.DomainFlowUtils.validateFeeChallenge;
import static google.registry.flows.domain.DomainFlowUtils.verifyNotReserved;
import static google.registry.flows.domain.DomainFlowUtils.verifyPremiumNameIsNotBlocked;
import static google.registry.flows.domain.DomainFlowUtils.verifyRegistrarIsActive;
import static google.registry.model.ResourceTransferUtils.updateForeignKeyIndexDeletionTime;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InternetDomainName;
import com.googlecode.objectify.Key;
import google.registry.dns.DnsQueue;
import google.registry.flows.EppException;
import google.registry.flows.EppException.CommandUseErrorException;
import google.registry.flows.EppException.StatusProhibitsOperationException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.ClientId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.TransactionalFlow;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainBase;
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
import google.registry.model.registry.Registry;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import java.util.Optional;
import javax.inject.Inject;
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
 * restore report that is submitted through an out of band process and details the request. However,
 * in practice this flow does the restore immediately. This is allowable because all of the fields
 * on a restore report are optional or have default values, and so by policy when the request comes
 * in we consider it to have been accompanied by a default-initialized report which we auto-approve.
 *
 * <p>Restores cost a fixed restore fee plus a one year renewal fee for the domain. The domain is
 * restored to a single year expiration starting at the restore time, regardless of what the
 * original expiration time was.
 *
 * @error {@link google.registry.flows.EppException.UnimplementedExtensionException}
 * @error {@link google.registry.flows.FlowUtils.UnknownCurrencyEppException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException}
 * @error {@link DomainFlowUtils.CurrencyUnitMismatchException}
 * @error {@link DomainFlowUtils.CurrencyValueScaleException}
 * @error {@link DomainFlowUtils.DomainReservedException}
 * @error {@link DomainFlowUtils.FeesMismatchException}
 * @error {@link DomainFlowUtils.FeesRequiredForPremiumNameException}
 * @error {@link DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link DomainFlowUtils.PremiumNameBlockedException}
 * @error {@link DomainFlowUtils.RegistrarMustBeActiveForThisOperationException}
 * @error {@link DomainFlowUtils.UnsupportedFeeAttributeException}
 * @error {@link DomainRestoreRequestFlow.DomainNotEligibleForRestoreException}
 * @error {@link DomainRestoreRequestFlow.RestoreCommandIncludesChangesException}
 */
@ReportingSpec(ActivityReportField.DOMAIN_RGP_RESTORE_REQUEST)
public final class DomainRestoreRequestFlow implements TransactionalFlow  {

  @Inject ResourceCommand resourceCommand;
  @Inject ExtensionManager extensionManager;
  @Inject EppInput eppInput;
  @Inject Optional<AuthInfo> authInfo;
  @Inject @ClientId String clientId;
  @Inject @TargetId String targetId;
  @Inject @Superuser boolean isSuperuser;
  @Inject DomainHistory.Builder historyBuilder;
  @Inject DnsQueue dnsQueue;
  @Inject EppResponse.Builder responseBuilder;
  @Inject DomainPricingLogic pricingLogic;
  @Inject DomainRestoreRequestFlow() {}

  @Override
  public final EppResponse run() throws EppException {
    extensionManager.register(
        FeeUpdateCommandExtension.class,
        MetadataExtension.class,
        RgpUpdateExtension.class);
    extensionManager.validate();
    validateClientIsLoggedIn(clientId);
    verifyRegistrarIsActive(clientId);
    Update command = (Update) resourceCommand;
    DateTime now = tm().getTransactionTime();
    DomainBase existingDomain = loadAndVerifyExistence(DomainBase.class, targetId, now);
    boolean isExpired = existingDomain.getRegistrationExpirationTime().isBefore(now);
    FeesAndCredits feesAndCredits =
        pricingLogic.getRestorePrice(
            Registry.get(existingDomain.getTld()), targetId, now, isExpired);
    Optional<FeeUpdateCommandExtension> feeUpdate =
        eppInput.getSingleExtension(FeeUpdateCommandExtension.class);
    verifyRestoreAllowed(command, existingDomain, feeUpdate, feesAndCredits, now);
    Key<DomainHistory> domainHistoryKey = createHistoryKey(existingDomain, DomainHistory.class);
    historyBuilder.setId(domainHistoryKey.getId());
    ImmutableSet.Builder<ImmutableObject> entitiesToSave = new ImmutableSet.Builder<>();

    DateTime newExpirationTime =
        existingDomain.getRegistrationExpirationTime().plusYears(isExpired ? 1 : 0);
    // Restore the expiration time on the deleted domain, except if that's already passed, then add
    // a year and bill for it immediately, with no grace period.
    if (isExpired) {
      entitiesToSave.add(
          createRenewBillingEvent(domainHistoryKey, feesAndCredits.getRenewCost(), now));
    }
    // Always bill for the restore itself.
    entitiesToSave.add(
        createRestoreBillingEvent(domainHistoryKey, feesAndCredits.getRestoreCost(), now));

    BillingEvent.Recurring autorenewEvent =
        newAutorenewBillingEvent(existingDomain)
            .setEventTime(newExpirationTime)
            .setRecurrenceEndTime(END_OF_TIME)
            .setParent(domainHistoryKey)
            .build();
    PollMessage.Autorenew autorenewPollMessage =
        newAutorenewPollMessage(existingDomain)
            .setEventTime(newExpirationTime)
            .setAutorenewEndTime(END_OF_TIME)
            .setParentKey(domainHistoryKey)
            .build();
    DomainBase newDomain =
        performRestore(
            existingDomain, newExpirationTime, autorenewEvent, autorenewPollMessage, now, clientId);
    updateForeignKeyIndexDeletionTime(newDomain);
    DomainHistory domainHistory = buildDomainHistory(newDomain, now);
    entitiesToSave.add(newDomain, domainHistory, autorenewEvent, autorenewPollMessage);
    tm().putAll(entitiesToSave.build());
    tm().delete(existingDomain.getDeletePollMessage());
    dnsQueue.addDomainRefreshTask(existingDomain.getDomainName());
    return responseBuilder
        .setExtensions(createResponseExtensions(feesAndCredits, feeUpdate, isExpired))
        .build();
  }

  private DomainHistory buildDomainHistory(DomainBase newDomain, DateTime now) {
    return historyBuilder
        .setType(HistoryEntry.Type.DOMAIN_RESTORE)
        .setModificationTime(now)
        .setDomain(newDomain)
        .setDomainTransactionRecords(
            ImmutableSet.of(
                DomainTransactionRecord.create(
                    newDomain.getTld(), now, TransactionReportField.RESTORED_DOMAINS, 1)))
        .build();
  }

  private void verifyRestoreAllowed(
      Update command,
      DomainBase existingDomain,
      Optional<FeeUpdateCommandExtension> feeUpdate,
      FeesAndCredits feesAndCredits,
      DateTime now) throws EppException {
    verifyOptionalAuthInfo(authInfo, existingDomain);
    if (!isSuperuser) {
      verifyResourceOwnership(clientId, existingDomain);
      verifyNotReserved(InternetDomainName.from(targetId), false);
      verifyPremiumNameIsNotBlocked(targetId, now, clientId);
      checkAllowedAccessToTld(clientId, existingDomain.getTld());
    }
    // No other changes can be specified on a restore request.
    if (!command.noChangesPresent()) {
      throw new RestoreCommandIncludesChangesException();
    }
    // Domain must be within the redemptionPeriod to be eligible for restore.
    if (!existingDomain.getGracePeriodStatuses().contains(GracePeriodStatus.REDEMPTION)) {
      throw new DomainNotEligibleForRestoreException();
    }
    validateFeeChallenge(targetId, now, feeUpdate, feesAndCredits);
  }

  private static DomainBase performRestore(
      DomainBase existingDomain,
      DateTime newExpirationTime,
      BillingEvent.Recurring autorenewEvent,
      PollMessage.Autorenew autorenewPollMessage,
      DateTime now,
      String clientId) {
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
        .setLastEppUpdateClientId(clientId)
        .build();
  }

  private OneTime createRenewBillingEvent(
      Key<DomainHistory> domainHistoryKey, Money renewCost, DateTime now) {
    return prepareBillingEvent(domainHistoryKey, renewCost, now).setReason(Reason.RENEW).build();
  }

  private BillingEvent.OneTime createRestoreBillingEvent(
      Key<DomainHistory> domainHistoryKey, Money restoreCost, DateTime now) {
    return prepareBillingEvent(domainHistoryKey, restoreCost, now)
        .setReason(Reason.RESTORE)
        .build();
  }

  private OneTime.Builder prepareBillingEvent(
      Key<DomainHistory> domainHistoryKey, Money cost, DateTime now) {
    return new BillingEvent.OneTime.Builder()
        .setTargetId(targetId)
        .setClientId(clientId)
        .setEventTime(now)
        .setBillingTime(now)
        .setPeriodYears(1)
        .setCost(cost)
        .setParent(domainHistoryKey);
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
    return feeUpdate.isPresent()
        ? ImmutableList.of(
            feeUpdate
                .get()
                .createResponseBuilder()
                .setCurrency(feesAndCredits.getCurrency())
                .setFees(fees.build())
                .build())
        : ImmutableList.of();
  }

  /** Restore command cannot have other changes specified. */
  static class RestoreCommandIncludesChangesException extends CommandUseErrorException {
    public RestoreCommandIncludesChangesException() {
      super("Restore command cannot have other changes specified");
    }
  }

  /** Domain is not eligible for restore. */
  static class DomainNotEligibleForRestoreException extends StatusProhibitsOperationException {
    public DomainNotEligibleForRestoreException() {
      super("Domain is not eligible for restore");
    }
  }
}
