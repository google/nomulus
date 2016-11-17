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

import static google.registry.flows.FlowUtils.validateClientIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.updateForeignKeyIndexDeletionTime;
import static google.registry.flows.ResourceFlowUtils.verifyOptionalAuthInfo;
import static google.registry.flows.ResourceFlowUtils.verifyResourceOwnership;
import static google.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static google.registry.flows.domain.DomainFlowUtils.newAutorenewBillingEvent;
import static google.registry.flows.domain.DomainFlowUtils.newAutorenewPollMessage;
import static google.registry.flows.domain.DomainFlowUtils.validateFeeChallenge;
import static google.registry.flows.domain.DomainFlowUtils.verifyNotReserved;
import static google.registry.flows.domain.DomainFlowUtils.verifyPremiumNameIsNotBlocked;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.base.Optional;
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
import google.registry.flows.domain.TldSpecificLogicProxy.EppCommandOperations;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.billing.BillingEvent.OneTime.Builder;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainCommand.Update;
import google.registry.model.domain.DomainResource;
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
import google.registry.model.reporting.HistoryEntry;
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
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException}
 * @error {@link DomainFlowUtils.CurrencyUnitMismatchException}
 * @error {@link DomainFlowUtils.CurrencyValueScaleException}
 * @error {@link DomainFlowUtils.DomainReservedException}
 * @error {@link DomainFlowUtils.FeesMismatchException}
 * @error {@link DomainFlowUtils.FeesRequiredForPremiumNameException}
 * @error {@link DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link DomainFlowUtils.PremiumNameBlockedException}
 * @error {@link DomainFlowUtils.UnsupportedFeeAttributeException}
 * @error {@link DomainRestoreRequestFlow.DomainNotEligibleForRestoreException}
 * @error {@link DomainRestoreRequestFlow.RestoreCommandIncludesChangesException}
 */
public final class DomainRestoreRequestFlow implements TransactionalFlow  {

  @Inject ResourceCommand resourceCommand;
  @Inject ExtensionManager extensionManager;
  @Inject EppInput eppInput;
  @Inject Optional<AuthInfo> authInfo;
  @Inject @ClientId String clientId;
  @Inject @TargetId String targetId;
  @Inject @Superuser boolean isSuperuser;
  @Inject HistoryEntry.Builder historyBuilder;
  @Inject DnsQueue dnsQueue;
  @Inject EppResponse.Builder responseBuilder;
  @Inject DomainRestoreRequestFlow() {}

  @Override
  public final EppResponse run() throws EppException {
    extensionManager.register(
        FeeUpdateCommandExtension.class,
        MetadataExtension.class,
        RgpUpdateExtension.class);
    extensionManager.validate();
    validateClientIsLoggedIn(clientId);
    Update command = (Update) resourceCommand;
    DateTime now = ofy().getTransactionTime();
    DomainResource existingDomain = loadAndVerifyExistence(DomainResource.class, targetId, now);
    Money restoreCost = Registry.get(existingDomain.getTld()).getStandardRestoreCost();
    EppCommandOperations renewCommandOperations = TldSpecificLogicProxy.getRenewPrice(
        Registry.get(existingDomain.getTld()), targetId, clientId, now, 1, eppInput);
    FeeUpdateCommandExtension feeUpdate =
        eppInput.getSingleExtension(FeeUpdateCommandExtension.class);
    Money totalCost = renewCommandOperations.getTotalCost();
    verifyRestoreAllowed(command, existingDomain, restoreCost, totalCost, feeUpdate, now);
    HistoryEntry historyEntry = buildHistory(existingDomain, now);
    ImmutableSet.Builder<ImmutableObject> entitiesToSave = new ImmutableSet.Builder<>();
    entitiesToSave.addAll(
        createRestoreAndRenewBillingEvents(historyEntry, restoreCost, totalCost, now));
    // We don't preserve the original expiration time of the domain when we restore, since doing so
    // would require us to know if they received a grace period refund when they deleted the domain,
    // and to charge them for that again. Instead, we just say that all restores get a fresh year of
    // registration and bill them for that accordingly.
    DateTime newExpirationTime = now.plusYears(1);
    BillingEvent.Recurring autorenewEvent = newAutorenewBillingEvent(existingDomain)
        .setEventTime(newExpirationTime)
        .setRecurrenceEndTime(END_OF_TIME)
        .setParent(historyEntry)
        .build();
    PollMessage.Autorenew autorenewPollMessage = newAutorenewPollMessage(existingDomain)
        .setEventTime(newExpirationTime)
        .setAutorenewEndTime(END_OF_TIME)
        .setParent(historyEntry)
        .build();
    // Handle extra flow logic, if any.
    Optional<RegistryExtraFlowLogic> extraFlowLogic =
        RegistryExtraFlowLogicProxy.newInstanceForDomain(existingDomain);
    if (extraFlowLogic.isPresent()) {
      extraFlowLogic.get().performAdditionalDomainRestoreLogic(
          existingDomain, clientId, now, eppInput, historyEntry);
    }
    DomainResource newDomain =
        performRestore(existingDomain, newExpirationTime, autorenewEvent, autorenewPollMessage);
    updateForeignKeyIndexDeletionTime(newDomain);
    entitiesToSave.add(newDomain, historyEntry, autorenewEvent, autorenewPollMessage);
    ofy().save().entities(entitiesToSave.build());
    ofy().delete().key(existingDomain.getDeletePollMessage());
    dnsQueue.addDomainRefreshTask(existingDomain.getFullyQualifiedDomainName());
    return responseBuilder
        .setExtensions(createResponseExtensions(restoreCost, totalCost, feeUpdate))
        .build();
  }

  private HistoryEntry buildHistory(DomainResource existingDomain, DateTime now) {
    return historyBuilder
        .setType(HistoryEntry.Type.DOMAIN_RESTORE)
        .setModificationTime(now)
        .setParent(Key.create(existingDomain))
        .build();
  }

  private void verifyRestoreAllowed(
      Update command,
      DomainResource existingDomain,
      Money restoreCost,
      Money renewCost,
      FeeUpdateCommandExtension feeUpdate,
      DateTime now) throws EppException {
    verifyOptionalAuthInfo(authInfo, existingDomain);
    if (!isSuperuser) {
      verifyResourceOwnership(clientId, existingDomain);
      verifyNotReserved(InternetDomainName.from(targetId), false);
      verifyPremiumNameIsNotBlocked(targetId, now, clientId);
    }
    // No other changes can be specified on a restore request.
    if (!command.noChangesPresent()) {
      throw new RestoreCommandIncludesChangesException();
    }
    // Domain must be within the redemptionPeriod to be eligible for restore.
    if (!existingDomain.getGracePeriodStatuses().contains(GracePeriodStatus.REDEMPTION)) {
      throw new DomainNotEligibleForRestoreException();
    }
    checkAllowedAccessToTld(clientId, existingDomain.getTld());
    validateFeeChallenge(targetId, existingDomain.getTld(), now, feeUpdate, restoreCost, renewCost);
  }

  private ImmutableSet<BillingEvent.OneTime> createRestoreAndRenewBillingEvents(
      HistoryEntry historyEntry, Money restoreCost, Money renewCost, DateTime now) {
    // Bill for the restore.
    BillingEvent.OneTime restoreEvent = createRestoreBillingEvent(historyEntry, restoreCost, now);
    // Create a new autorenew billing event and poll message starting at the new expiration time.
    // Also bill for the 1 year cost of a domain renew. This is to avoid registrants being able to
    // game the system for premium names by renewing, deleting, and then restoring to get a free
    // year. Note that this billing event has no grace period; it is effective immediately.
    BillingEvent.OneTime renewEvent = createRenewBillingEvent(historyEntry, renewCost, now);
    return ImmutableSet.of(restoreEvent, renewEvent);
  }

  private static DomainResource performRestore(
      DomainResource existingDomain,
      DateTime newExpirationTime,
      BillingEvent.Recurring autorenewEvent,
      PollMessage.Autorenew autorenewPollMessage) {
    return existingDomain.asBuilder()
        .setRegistrationExpirationTime(newExpirationTime)
        .setDeletionTime(END_OF_TIME)
        .setStatusValues(null)
        .setGracePeriods(null)
        .setDeletePollMessage(null)
        .setAutorenewBillingEvent(Key.create(autorenewEvent))
        .setAutorenewPollMessage(Key.create(autorenewPollMessage))
        .build();
  }

  private OneTime createRenewBillingEvent(
      HistoryEntry historyEntry, Money renewCost, DateTime now) {
    return prepareBillingEvent(historyEntry, renewCost, now)
        .setPeriodYears(1)
        .setReason(Reason.RENEW)
        .build();
  }

  private BillingEvent.OneTime createRestoreBillingEvent(
      HistoryEntry historyEntry, Money restoreCost, DateTime now) {
    return prepareBillingEvent(historyEntry, restoreCost, now)
        .setReason(Reason.RESTORE)
        .build();
  }

  private Builder prepareBillingEvent(HistoryEntry historyEntry, Money cost, DateTime now) {
    return new BillingEvent.OneTime.Builder()
        .setTargetId(targetId)
        .setClientId(clientId)
        .setEventTime(now)
        .setBillingTime(now)
        .setCost(cost)
        .setParent(historyEntry);
  }

  private static ImmutableList<FeeTransformResponseExtension> createResponseExtensions(
      Money restoreCost, Money renewCost, FeeUpdateCommandExtension feeUpdate) {
    return (feeUpdate == null)
        ? ImmutableList.<FeeTransformResponseExtension>of()
        : ImmutableList.of(feeUpdate.createResponseBuilder()
            .setCurrency(restoreCost.getCurrencyUnit())
            .setFees(ImmutableList.of(
                Fee.create(restoreCost.getAmount(), FeeType.RESTORE),
                Fee.create(renewCost.getAmount(), FeeType.RENEW)))
            .build());
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
