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

package google.registry.flows.domain;

import static google.registry.flows.FlowUtils.persistEntityChanges;
import static google.registry.flows.FlowUtils.validateClientIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyNoDisallowedStatuses;
import static google.registry.flows.ResourceFlowUtils.verifyOptionalAuthInfo;
import static google.registry.flows.ResourceFlowUtils.verifyResourceOwnership;
import static google.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static google.registry.flows.domain.DomainFlowUtils.newAutorenewBillingEvent;
import static google.registry.flows.domain.DomainFlowUtils.newAutorenewPollMessage;
import static google.registry.flows.domain.DomainFlowUtils.updateAutorenewRecurrenceEndTime;
import static google.registry.flows.domain.DomainFlowUtils.validateFeeChallenge;
import static google.registry.flows.domain.DomainFlowUtils.verifyUnitIsYears;
import static google.registry.model.domain.DomainResource.MAX_REGISTRATION_YEARS;
import static google.registry.model.domain.DomainResource.extendRegistrationWithCap;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.DateTimeUtils.leapSafeAddYears;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.flows.EppException;
import google.registry.flows.EppException.ObjectPendingTransferException;
import google.registry.flows.EppException.ParameterValueRangeErrorException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.ClientId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.TransactionalFlow;
import google.registry.flows.custom.DomainRenewFlowCustomLogic;
import google.registry.flows.custom.DomainRenewFlowCustomLogic.AfterValidationParameters;
import google.registry.flows.custom.DomainRenewFlowCustomLogic.BeforeResponseParameters;
import google.registry.flows.custom.DomainRenewFlowCustomLogic.BeforeResponseReturnData;
import google.registry.flows.custom.DomainRenewFlowCustomLogic.BeforeSaveParameters;
import google.registry.flows.custom.EntityChanges;
import google.registry.flows.domain.TldSpecificLogicProxy.EppCommandOperations;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainCommand.Renew;
import google.registry.model.domain.DomainRenewData;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.fee.BaseFee.FeeType;
import google.registry.model.domain.fee.Fee;
import google.registry.model.domain.fee.FeeRenewCommandExtension;
import google.registry.model.domain.fee.FeeTransformResponseExtension;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferStatus;
import javax.inject.Inject;
import org.joda.money.Money;
import org.joda.time.DateTime;

/**
 * An EPP flow that renews a domain.
 *
 * <p>Registrars can use this flow to manually extend the length of a registration, instead of
 * relying on domain auto-renewal (where the registry performs an automatic one-year renewal at the
 * instant a domain would expire).
 *
 * <p>ICANN prohibits any registration from being longer than ten years so if the request would
 * result in a registration greater than ten years long it will fail. In practice this means it's
 * impossible to request a ten year renewal, since that will always cause the new registration to be
 * longer than 10 years unless it comes in at the exact millisecond that the domain would have
 * expired.
 *
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException}
 * @error {@link google.registry.flows.exceptions.ResourceStatusProhibitsOperationException}
 * @error {@link DomainFlowUtils.BadPeriodUnitException}
 * @error {@link DomainFlowUtils.CurrencyUnitMismatchException}
 * @error {@link DomainFlowUtils.CurrencyValueScaleException}
 * @error {@link DomainFlowUtils.FeesMismatchException}
 * @error {@link DomainFlowUtils.FeesRequiredForPremiumNameException}
 * @error {@link DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link DomainFlowUtils.UnsupportedFeeAttributeException}
 * @error {@link DomainRenewFlow.DomainHasPendingTransferException}
 * @error {@link DomainRenewFlow.ExceedsMaxRegistrationYearsException}
 * @error {@link DomainRenewFlow.IncorrectCurrentExpirationDateException}
 */
public final class DomainRenewFlow implements TransactionalFlow {

  private static final ImmutableSet<StatusValue> RENEW_DISALLOWED_STATUSES = ImmutableSet.of(
      StatusValue.CLIENT_RENEW_PROHIBITED,
      StatusValue.PENDING_DELETE,
      StatusValue.SERVER_RENEW_PROHIBITED);

  @Inject ResourceCommand resourceCommand;
  @Inject ExtensionManager extensionManager;
  @Inject EppInput eppInput;
  @Inject Optional<AuthInfo> authInfo;
  @Inject @ClientId String clientId;
  @Inject @TargetId String targetId;
  @Inject @Superuser boolean isSuperuser;
  @Inject HistoryEntry.Builder historyBuilder;
  @Inject EppResponse.Builder responseBuilder;
  @Inject DomainRenewFlowCustomLogic customLogic;
  @Inject DomainRenewFlow() {}

  @Override
  public final EppResponse run() throws EppException {
    extensionManager.register(FeeRenewCommandExtension.class, MetadataExtension.class);
    customLogic.beforeValidation();
    extensionManager.validate();
    validateClientIsLoggedIn(clientId);
    DateTime now = ofy().getTransactionTime();
    Renew command = (Renew) resourceCommand;
    // Loads the target resource if it exists
    DomainResource existingDomain = loadAndVerifyExistence(DomainResource.class, targetId, now);
    verifyRenewAllowed(authInfo, existingDomain, command);
    int years = command.getPeriod().getValue();
    FeeRenewCommandExtension feeRenew =
        eppInput.getSingleExtension(FeeRenewCommandExtension.class);
    EppCommandOperations commandOperations = TldSpecificLogicProxy.getRenewPrice(
        Registry.get(existingDomain.getTld()), targetId, clientId, now, years, eppInput);
    validateFeeChallenge(
        targetId, existingDomain.getTld(), now, feeRenew, commandOperations.getTotalCost());
    customLogic.afterValidation(
        AfterValidationParameters.newBuilder()
            .setExistingDomain(existingDomain)
            .setNow(now)
            .setYears(years)
            .build());
    HistoryEntry historyEntry = historyBuilder
        .setType(HistoryEntry.Type.DOMAIN_RENEW)
        .setPeriod(command.getPeriod())
        .setModificationTime(now)
        .setParent(Key.create(existingDomain))
        .build();
    DateTime oldExpirationTime = existingDomain.getRegistrationExpirationTime();
    DateTime newExpirationTime = leapSafeAddYears(oldExpirationTime, years);  // Uncapped
    if (extendRegistrationWithCap(now, oldExpirationTime, years).isBefore(newExpirationTime)) {
      throw new ExceedsMaxRegistrationYearsException();
    }
    String tld = existingDomain.getTld();
    // Bill for this explicit renew itself.
    BillingEvent.OneTime explicitRenewEvent =
        createRenewBillingEvent(tld, commandOperations.getTotalCost(), years, historyEntry, now);
    // Create a new autorenew billing event and poll message starting at the new expiration time.
    BillingEvent.Recurring newAutorenewEvent = newAutorenewBillingEvent(existingDomain)
        .setEventTime(newExpirationTime)
        .setParent(historyEntry)
        .build();
    PollMessage.Autorenew newAutorenewPollMessage = newAutorenewPollMessage(existingDomain)
        .setEventTime(newExpirationTime)
        .setParent(historyEntry)
        .build();
    // End the old autorenew billing event and poll message now. This may delete the poll message.
    updateAutorenewRecurrenceEndTime(existingDomain, now);
    // Handle extra flow logic, if any.
    Optional<RegistryExtraFlowLogic> extraFlowLogic =
        RegistryExtraFlowLogicProxy.newInstanceForDomain(existingDomain);
    if (extraFlowLogic.isPresent()) {
      extraFlowLogic.get().performAdditionalDomainRenewLogic(
          existingDomain, clientId, now, years, eppInput, historyEntry);
    }
    DomainResource newDomain = existingDomain.asBuilder()
        .setRegistrationExpirationTime(newExpirationTime)
        .setAutorenewBillingEvent(Key.create(newAutorenewEvent))
        .setAutorenewPollMessage(Key.create(newAutorenewPollMessage))
        .addGracePeriod(GracePeriod.forBillingEvent(GracePeriodStatus.RENEW, explicitRenewEvent))
        .build();
    EntityChanges entityChanges =
        customLogic.beforeSave(
            BeforeSaveParameters.newBuilder()
                .setExistingDomain(existingDomain)
                .setNewDomain(newDomain)
                .setNow(now)
                .setYears(years)
                .setHistoryEntry(historyEntry)
                .setEntityChanges(
                    EntityChanges.newBuilder()
                        .setSaves(
                            ImmutableSet.<ImmutableObject>of(
                                newDomain,
                                historyEntry,
                                explicitRenewEvent,
                                newAutorenewEvent,
                                newAutorenewPollMessage))
                        .build())
                .build());
    persistEntityChanges(entityChanges);
    BeforeResponseReturnData responseData =
        customLogic.beforeResponse(
            BeforeResponseParameters.newBuilder()
                .setDomain(newDomain)
                .setResData(DomainRenewData.create(targetId, newExpirationTime))
                .setResponseExtensions(
                    createResponseExtensions(commandOperations.getTotalCost(), feeRenew))
                .build());
    return responseBuilder
        .setResData(responseData.resData())
        .setExtensions(responseData.responseExtensions())
        .build();
  }

  private void verifyRenewAllowed(
      Optional<AuthInfo> authInfo,
      DomainResource existingDomain,
      Renew command) throws EppException {
    verifyOptionalAuthInfo(authInfo, existingDomain);
    verifyNoDisallowedStatuses(existingDomain, RENEW_DISALLOWED_STATUSES);
    if (!isSuperuser) {
      verifyResourceOwnership(clientId, existingDomain);
    }
    checkAllowedAccessToTld(clientId, existingDomain.getTld());
    // Verify that the resource does not have a pending transfer on it.
    if (existingDomain.getTransferData().getTransferStatus() == TransferStatus.PENDING) {
      throw new DomainHasPendingTransferException(targetId);
    }
    verifyUnitIsYears(command.getPeriod());
    // If the date they specify doesn't match the expiration, fail. (This is an idempotence check).
    if (!command.getCurrentExpirationDate().equals(
        existingDomain.getRegistrationExpirationTime().toLocalDate())) {
      throw new IncorrectCurrentExpirationDateException();
    }
  }

  private OneTime createRenewBillingEvent(
      String tld, Money renewCost, int years, HistoryEntry historyEntry, DateTime now) {
    return new BillingEvent.OneTime.Builder()
        .setReason(Reason.RENEW)
        .setTargetId(targetId)
        .setClientId(clientId)
        .setPeriodYears(years)
        .setCost(renewCost)
        .setEventTime(now)
        .setBillingTime(now.plus(Registry.get(tld).getRenewGracePeriodLength()))
        .setParent(historyEntry)
        .build();
  }

  private ImmutableList<FeeTransformResponseExtension> createResponseExtensions(
      Money renewCost, FeeRenewCommandExtension feeRenew) {
    return (feeRenew == null)
        ? ImmutableList.<FeeTransformResponseExtension>of()
        : ImmutableList.of(feeRenew.createResponseBuilder()
            .setCurrency(renewCost.getCurrencyUnit())
            .setFees(ImmutableList.of(Fee.create(renewCost.getAmount(), FeeType.RENEW)))
            .build());
  }

  /** The domain has a pending transfer on it and so can't be explicitly renewed. */
  public static class DomainHasPendingTransferException extends ObjectPendingTransferException {
    public DomainHasPendingTransferException(String targetId) {
      super(targetId);
    }
  }

  /** The current expiration date is incorrect. */
  static class IncorrectCurrentExpirationDateException extends ParameterValueRangeErrorException {
    public IncorrectCurrentExpirationDateException() {
      super("The current expiration date is incorrect");
    }
  }

  /** New registration period exceeds maximum number of years. */
  static class ExceedsMaxRegistrationYearsException extends ParameterValueRangeErrorException {
    public ExceedsMaxRegistrationYearsException() {
      super(String.format(
          "Registrations cannot extend for more than %d years into the future",
          MAX_REGISTRATION_YEARS));
    }
  }
}
