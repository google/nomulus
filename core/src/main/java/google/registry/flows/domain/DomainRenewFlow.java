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

import static google.registry.flows.FlowUtils.createHistoryEntryId;
import static google.registry.flows.FlowUtils.persistEntityChanges;
import static google.registry.flows.FlowUtils.validateRegistrarIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyNoDisallowedStatuses;
import static google.registry.flows.ResourceFlowUtils.verifyOptionalAuthInfo;
import static google.registry.flows.ResourceFlowUtils.verifyResourceOwnership;
import static google.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static google.registry.flows.domain.DomainFlowUtils.checkHasBillingAccount;
import static google.registry.flows.domain.DomainFlowUtils.newAutorenewBillingEvent;
import static google.registry.flows.domain.DomainFlowUtils.newAutorenewPollMessage;
import static google.registry.flows.domain.DomainFlowUtils.updateAutorenewRecurrenceEndTime;
import static google.registry.flows.domain.DomainFlowUtils.validateFeeChallenge;
import static google.registry.flows.domain.DomainFlowUtils.validateRegistrationPeriod;
import static google.registry.flows.domain.DomainFlowUtils.verifyRegistrarIsActive;
import static google.registry.flows.domain.DomainFlowUtils.verifyUnitIsYears;
import static google.registry.flows.domain.token.AllocationTokenFlowUtils.maybeApplyBulkPricingRemovalToken;
import static google.registry.flows.domain.token.AllocationTokenFlowUtils.verifyBulkTokenAllowedOnDomain;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_RENEW;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.leapSafeAddYears;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.flows.EppException;
import google.registry.flows.EppException.ParameterValueRangeErrorException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.MutatingFlow;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.flows.custom.DomainRenewFlowCustomLogic;
import google.registry.flows.custom.DomainRenewFlowCustomLogic.AfterValidationParameters;
import google.registry.flows.custom.DomainRenewFlowCustomLogic.BeforeResponseParameters;
import google.registry.flows.custom.DomainRenewFlowCustomLogic.BeforeResponseReturnData;
import google.registry.flows.custom.DomainRenewFlowCustomLogic.BeforeSaveParameters;
import google.registry.flows.custom.EntityChanges;
import google.registry.flows.domain.token.AllocationTokenFlowUtils;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.MissingRemoveBulkPricingTokenOnBulkPricingDomainException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.RemoveBulkPricingTokenOnNonBulkPricingDomainException;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainCommand.Renew;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.DomainRenewData;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.Period;
import google.registry.model.domain.fee.BaseFee.FeeType;
import google.registry.model.domain.fee.Fee;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import google.registry.model.domain.fee.FeeRenewCommandExtension;
import google.registry.model.domain.fee.FeeTransformResponseExtension;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationTokenExtension;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppcommon.StatusValue;
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
import org.joda.time.Duration;

/**
 * An EPP flow that renews a domain.
 *
 * <p>Registrars can use this flow to manually extend the length of a registration, instead of
 * relying on domain auto-renewal (where the registry performs an automatic one-year renewal at the
 * instant a domain would expire).
 *
 * <p>ICANN prohibits any registration from being longer than ten years so if the request would
 * result in a registration greater than ten years long it will fail. In practice this means it's
 * impossible to request a ten-year renewal, since that will always cause the new registration to be
 * longer than 10 years unless it comes in at the exact millisecond that the domain would have
 * expired.
 *
 * @error {@link google.registry.flows.FlowUtils.NotLoggedInException}
 * @error {@link google.registry.flows.FlowUtils.UnknownCurrencyEppException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException}
 * @error {@link google.registry.flows.exceptions.ResourceStatusProhibitsOperationException}
 * @error {@link DomainFlowUtils.BadPeriodUnitException}
 * @error {@link DomainFlowUtils.CurrencyUnitMismatchException}
 * @error {@link DomainFlowUtils.CurrencyValueScaleException}
 * @error {@link DomainFlowUtils.ExceedsMaxRegistrationYearsException}
 * @error {@link DomainFlowUtils.FeesMismatchException}
 * @error {@link DomainFlowUtils.FeesRequiredForPremiumNameException}
 * @error {@link DomainFlowUtils.MissingBillingAccountMapException}
 * @error {@link DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link DomainFlowUtils.RegistrarMustBeActiveForThisOperationException}
 * @error {@link DomainFlowUtils.UnsupportedFeeAttributeException}
 * @error {@link DomainRenewFlow.IncorrectCurrentExpirationDateException}
 * @error {@link MissingRemoveBulkPricingTokenOnBulkPricingDomainException}
 * @error {@link RemoveBulkPricingTokenOnNonBulkPricingDomainException}
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
@ReportingSpec(ActivityReportField.DOMAIN_RENEW)
public final class DomainRenewFlow implements MutatingFlow {

  private static final ImmutableSet<StatusValue> RENEW_DISALLOWED_STATUSES = ImmutableSet.of(
      StatusValue.CLIENT_RENEW_PROHIBITED,
      StatusValue.PENDING_DELETE,
      // Disallow renews during pendingTransfer; it needlessly complicates server-approve transfers.
      StatusValue.PENDING_TRANSFER,
      StatusValue.SERVER_RENEW_PROHIBITED);

  @Inject ResourceCommand resourceCommand;
  @Inject ExtensionManager extensionManager;
  @Inject EppInput eppInput;
  @Inject Optional<AuthInfo> authInfo;
  @Inject @RegistrarId String registrarId;
  @Inject @TargetId String targetId;
  @Inject @Superuser boolean isSuperuser;
  @Inject DomainHistory.Builder historyBuilder;
  @Inject EppResponse.Builder responseBuilder;
  @Inject DomainRenewFlowCustomLogic flowCustomLogic;
  @Inject DomainPricingLogic pricingLogic;
  @Inject DomainRenewFlow() {}

  @Override
  public EppResponse run() throws EppException {
    extensionManager.register(
        FeeRenewCommandExtension.class, MetadataExtension.class, AllocationTokenExtension.class);
    flowCustomLogic.beforeValidation();
    validateRegistrarIsLoggedIn(registrarId);
    verifyRegistrarIsActive(registrarId);
    extensionManager.validate();
    DateTime now = tm().getTransactionTime();
    Renew command = (Renew) resourceCommand;
    // Loads the target resource if it exists
    Domain existingDomain = loadAndVerifyExistence(Domain.class, targetId, now);
    String tldStr = existingDomain.getTld();
    Tld tld = Tld.get(tldStr);
    Optional<AllocationToken> allocationToken =
        AllocationTokenFlowUtils.loadTokenFromExtensionOrGetDefault(
            registrarId,
            now,
            eppInput.getSingleExtension(AllocationTokenExtension.class),
            tld,
            existingDomain.getDomainName(),
            CommandName.RENEW);
    boolean defaultTokenUsed =
        allocationToken
            .map(t -> t.getTokenType().equals(AllocationToken.TokenType.DEFAULT_PROMO))
            .orElse(false);
    verifyRenewAllowed(authInfo, existingDomain, command, allocationToken);

    // If client passed an applicable static token this updates the domain
    existingDomain = maybeApplyBulkPricingRemovalToken(existingDomain, allocationToken);

    int years = command.getPeriod().getValue();
    DateTime newExpirationTime =
        leapSafeAddYears(existingDomain.getRegistrationExpirationTime(), years);  // Uncapped
    validateRegistrationPeriod(now, newExpirationTime);
    Optional<FeeRenewCommandExtension> feeRenew =
        eppInput.getSingleExtension(FeeRenewCommandExtension.class);
    BillingRecurrence existingBillingRecurrence =
        tm().loadByKey(existingDomain.getAutorenewBillingEvent());
    FeesAndCredits feesAndCredits =
        pricingLogic.getRenewPrice(
            Tld.get(existingDomain.getTld()),
            targetId,
            now,
            years,
            existingBillingRecurrence,
            allocationToken);
    validateFeeChallenge(feeRenew, feesAndCredits, defaultTokenUsed);
    flowCustomLogic.afterValidation(
        AfterValidationParameters.newBuilder()
            .setExistingDomain(existingDomain)
            .setNow(now)
            .setYears(years)
            .build());
    HistoryEntryId domainHistoryId = createHistoryEntryId(existingDomain);
    historyBuilder.setRevisionId(domainHistoryId.getRevisionId());
    // Bill for this explicit renew itself.
    BillingEvent explicitRenewEvent =
        createRenewBillingEvent(
            tldStr, feesAndCredits.getTotalCost(), years, domainHistoryId, allocationToken, now);
    // Create a new autorenew billing event and poll message starting at the new expiration time.
    BillingRecurrence newAutorenewEvent =
        newAutorenewBillingEvent(existingDomain)
            .setEventTime(newExpirationTime)
            .setRenewalPrice(existingBillingRecurrence.getRenewalPrice().orElse(null))
            .setRenewalPriceBehavior(existingBillingRecurrence.getRenewalPriceBehavior())
            .setDomainHistoryId(domainHistoryId)
            .build();
    PollMessage.Autorenew newAutorenewPollMessage =
        newAutorenewPollMessage(existingDomain)
            .setEventTime(newExpirationTime)
            .setDomainHistoryId(domainHistoryId)
            .build();
    // End the old autorenew billing event and poll message now. This may delete the poll message.
    updateAutorenewRecurrenceEndTime(
        existingDomain, existingBillingRecurrence, now, domainHistoryId);
    Domain newDomain =
        existingDomain
            .asBuilder()
            .setLastEppUpdateTime(now)
            .setLastEppUpdateRegistrarId(registrarId)
            .setRegistrationExpirationTime(newExpirationTime)
            .setAutorenewBillingEvent(newAutorenewEvent.createVKey())
            .setAutorenewPollMessage(newAutorenewPollMessage.createVKey())
            .addGracePeriod(
                GracePeriod.forBillingEvent(
                    GracePeriodStatus.RENEW, existingDomain.getRepoId(), explicitRenewEvent))
            .build();
    DomainHistory domainHistory =
        buildDomainHistory(newDomain, now, command.getPeriod(), tld.getRenewGracePeriodLength());
    ImmutableSet.Builder<ImmutableObject> entitiesToSave = new ImmutableSet.Builder<>();
    entitiesToSave.add(
        newDomain, domainHistory, explicitRenewEvent, newAutorenewEvent, newAutorenewPollMessage);
    if (allocationToken.isPresent() && allocationToken.get().getTokenType().isOneTimeUse()) {
      entitiesToSave.add(
          AllocationTokenFlowUtils.redeemToken(
              allocationToken.get(), domainHistory.getHistoryEntryId()));
    }
    EntityChanges entityChanges =
        flowCustomLogic.beforeSave(
            BeforeSaveParameters.newBuilder()
                .setExistingDomain(existingDomain)
                .setNewDomain(newDomain)
                .setNow(now)
                .setYears(years)
                .setHistoryEntry(domainHistory)
                .setEntityChanges(
                    EntityChanges.newBuilder().setSaves(entitiesToSave.build()).build())
                .build());
    BeforeResponseReturnData responseData =
        flowCustomLogic.beforeResponse(
            BeforeResponseParameters.newBuilder()
                .setDomain(newDomain)
                .setResData(DomainRenewData.create(targetId, newExpirationTime))
                .setResponseExtensions(createResponseExtensions(feesAndCredits, feeRenew))
                .build());
    persistEntityChanges(entityChanges);
    return responseBuilder
        .setResData(responseData.resData())
        .setExtensions(responseData.responseExtensions())
        .build();
  }

  private DomainHistory buildDomainHistory(
      Domain newDomain, DateTime now, Period period, Duration renewGracePeriod) {
    Optional<MetadataExtension> metadataExtensionOpt =
        eppInput.getSingleExtension(MetadataExtension.class);
    if (metadataExtensionOpt.isPresent()) {
      MetadataExtension metadataExtension = metadataExtensionOpt.get();
      if (metadataExtension.getReason() != null) {
        historyBuilder.setReason(metadataExtension.getReason());
      }
      historyBuilder.setRequestedByRegistrar(metadataExtension.getRequestedByRegistrar());
    }
    return historyBuilder
        .setType(DOMAIN_RENEW)
        .setPeriod(period)
        .setDomain(newDomain)
        .setDomainTransactionRecords(
            ImmutableSet.of(
                DomainTransactionRecord.create(
                    newDomain.getTld(),
                    now.plus(renewGracePeriod),
                    TransactionReportField.netRenewsFieldFromYears(period.getValue()),
                    1)))
        .build();
  }

  private void verifyRenewAllowed(
      Optional<AuthInfo> authInfo,
      Domain existingDomain,
      Renew command,
      Optional<AllocationToken> allocationToken)
      throws EppException {
    verifyOptionalAuthInfo(authInfo, existingDomain);
    verifyNoDisallowedStatuses(existingDomain, RENEW_DISALLOWED_STATUSES);
    if (!isSuperuser) {
      verifyResourceOwnership(registrarId, existingDomain);
      checkAllowedAccessToTld(registrarId, existingDomain.getTld());
      checkHasBillingAccount(registrarId, existingDomain.getTld());
    }
    verifyUnitIsYears(command.getPeriod());
    // We only allow __REMOVE_BULK_PRICING__ token on bulk pricing domains for now
    verifyBulkTokenAllowedOnDomain(existingDomain, allocationToken);
    // If the date they specify doesn't match the expiration, fail. (This is an idempotence check).
    if (!command.getCurrentExpirationDate().equals(
        existingDomain.getRegistrationExpirationTime().toLocalDate())) {
      throw new IncorrectCurrentExpirationDateException();
    }
  }

  private BillingEvent createRenewBillingEvent(
      String tld,
      Money renewCost,
      int years,
      HistoryEntryId domainHistoryId,
      Optional<AllocationToken> allocationToken,
      DateTime now) {
    return new BillingEvent.Builder()
        .setReason(Reason.RENEW)
        .setTargetId(targetId)
        .setRegistrarId(registrarId)
        .setPeriodYears(years)
        .setCost(renewCost)
        .setEventTime(now)
        .setAllocationToken(
            allocationToken
                .filter(t -> AllocationToken.TokenBehavior.DEFAULT.equals(t.getTokenBehavior()))
                .map(AllocationToken::createVKey)
                .orElse(null))
        .setBillingTime(now.plus(Tld.get(tld).getRenewGracePeriodLength()))
        .setDomainHistoryId(domainHistoryId)
        .build();
  }

  private ImmutableList<FeeTransformResponseExtension> createResponseExtensions(
      FeesAndCredits feesAndCredits, Optional<FeeRenewCommandExtension> feeRenew) {
    return feeRenew
        .map(
            feeRenewCommandExtension ->
                ImmutableList.of(
                    feeRenewCommandExtension
                        .createResponseBuilder()
                        .setCurrency(feesAndCredits.getCurrency())
                        .setFees(
                            ImmutableList.of(
                                Fee.create(
                                    feesAndCredits.getRenewCost().getAmount(),
                                    FeeType.RENEW,
                                    feesAndCredits.hasPremiumFeesOfType(FeeType.RENEW))))
                        .build()))
        .orElseGet(ImmutableList::of);
  }

  /** The current expiration date is incorrect. */
  static class IncorrectCurrentExpirationDateException extends ParameterValueRangeErrorException {
    public IncorrectCurrentExpirationDateException() {
      super("The current expiration date is incorrect");
    }
  }
}
