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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.Sets.symmetricDifference;
import static com.google.common.collect.Sets.union;
import static google.registry.flows.FlowUtils.persistEntityChanges;
import static google.registry.flows.FlowUtils.validateClientIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.checkSameValuesNotAddedAndRemoved;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyAllStatusesAreClientSettable;
import static google.registry.flows.ResourceFlowUtils.verifyNoDisallowedStatuses;
import static google.registry.flows.ResourceFlowUtils.verifyOptionalAuthInfo;
import static google.registry.flows.ResourceFlowUtils.verifyResourceOwnership;
import static google.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static google.registry.flows.domain.DomainFlowUtils.cloneAndLinkReferences;
import static google.registry.flows.domain.DomainFlowUtils.updateDsData;
import static google.registry.flows.domain.DomainFlowUtils.validateContactsHaveTypes;
import static google.registry.flows.domain.DomainFlowUtils.validateDsData;
import static google.registry.flows.domain.DomainFlowUtils.validateFeeChallenge;
import static google.registry.flows.domain.DomainFlowUtils.validateNameserversAllowedOnTld;
import static google.registry.flows.domain.DomainFlowUtils.validateNameserversCountForTld;
import static google.registry.flows.domain.DomainFlowUtils.validateNoDuplicateContacts;
import static google.registry.flows.domain.DomainFlowUtils.validateRegistrantAllowedOnTld;
import static google.registry.flows.domain.DomainFlowUtils.validateRequiredContactsPresent;
import static google.registry.flows.domain.DomainFlowUtils.verifyClientUpdateNotProhibited;
import static google.registry.flows.domain.DomainFlowUtils.verifyNotInPendingDelete;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.DateTimeUtils.earliestOf;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.dns.DnsQueue;
import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.ClientId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.TransactionalFlow;
import google.registry.flows.custom.DomainUpdateFlowCustomLogic;
import google.registry.flows.custom.DomainUpdateFlowCustomLogic.AfterValidationParameters;
import google.registry.flows.custom.DomainUpdateFlowCustomLogic.BeforeSaveParameters;
import google.registry.flows.custom.EntityChanges;
import google.registry.flows.domain.DomainFlowUtils.FeesRequiredForNonFreeUpdateException;
import google.registry.flows.domain.TldSpecificLogicProxy.EppCommandOperations;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainCommand.Update;
import google.registry.model.domain.DomainCommand.Update.AddRemove;
import google.registry.model.domain.DomainCommand.Update.Change;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.fee.FeeTransformCommandExtension;
import google.registry.model.domain.fee.FeeUpdateCommandExtension;
import google.registry.model.domain.flags.FlagsUpdateCommandExtension;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.SecDnsUpdateExtension;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import javax.inject.Inject;
import org.joda.money.Money;
import org.joda.time.DateTime;

/**
 * An EPP flow that updates a domain.
 *
 * <p>Updates can change contacts, nameservers and delegation signer data of a domain. Updates
 * cannot change the domain's name.
 *
 * <p>Some status values (those of the form "serverSomethingProhibited") can only be applied by the
 * superuser. As such, adding or removing these statuses incurs a billing event. There will be only
 * one charge per update, even if several such statuses are updated at once.
 *
 * <p>If a domain was created during the sunrise or landrush phases of a TLD, is still within the
 * sunrushAddGracePeriod and has not yet been delegated in DNS, then it will not yet have been
 * billed for. Any update that causes the name to be delegated (such * as adding nameservers or
 * removing a hold status) will cause the domain to convert to a normal create and be billed for
 * accordingly.
 *
 * @error {@link google.registry.flows.EppException.UnimplementedExtensionException}
 * @error {@link google.registry.flows.ResourceFlowUtils.AddRemoveSameValueException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException}
 * @error {@link google.registry.flows.ResourceFlowUtils.StatusNotClientSettableException}
 * @error {@link google.registry.flows.exceptions.OnlyToolCanPassMetadataException}
 * @error {@link google.registry.flows.exceptions.ResourceHasClientUpdateProhibitedException}
 * @error {@link google.registry.flows.exceptions.ResourceStatusProhibitsOperationException}
 * @error {@link DomainFlowUtils.DuplicateContactForRoleException}
 * @error {@link DomainFlowUtils.EmptySecDnsUpdateException}
 * @error {@link DomainFlowUtils.FeesMismatchException}
 * @error {@link DomainFlowUtils.FeesRequiredForNonFreeUpdateException}
 * @error {@link DomainFlowUtils.LinkedResourcesDoNotExistException}
 * @error {@link DomainFlowUtils.LinkedResourceInPendingDeleteProhibitsOperationException}
 * @error {@link DomainFlowUtils.MaxSigLifeChangeNotSupportedException}
 * @error {@link DomainFlowUtils.MissingAdminContactException}
 * @error {@link DomainFlowUtils.MissingContactTypeException}
 * @error {@link DomainFlowUtils.MissingTechnicalContactException}
 * @error {@link DomainFlowUtils.NameserversNotAllowedException}
 * @error {@link DomainFlowUtils.NameserversNotSpecifiedException}
 * @error {@link DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link DomainFlowUtils.RegistrantNotAllowedException}
 * @error {@link DomainFlowUtils.SecDnsAllUsageException}
 * @error {@link DomainFlowUtils.TooManyDsRecordsException}
 * @error {@link DomainFlowUtils.TooManyNameserversException}
 * @error {@link DomainFlowUtils.UrgentAttributeNotSupportedException}
 */
public final class DomainUpdateFlow implements TransactionalFlow {

  /**
   * Note that CLIENT_UPDATE_PROHIBITED is intentionally not in this list. This is because it
   * requires special checking, since you must be able to clear the status off the object with an
   * update.
   */
  private static final ImmutableSet<StatusValue> UPDATE_DISALLOWED_STATUSES =
      ImmutableSet.of(StatusValue.PENDING_DELETE, StatusValue.SERVER_UPDATE_PROHIBITED);

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
  @Inject DomainUpdateFlowCustomLogic customLogic;

  @Inject DomainUpdateFlow() {}

  @Override
  public EppResponse run() throws EppException {
    extensionManager.register(
        FeeUpdateCommandExtension.class,
        FlagsUpdateCommandExtension.class,
        MetadataExtension.class,
        SecDnsUpdateExtension.class);
    customLogic.beforeValidation();
    extensionManager.validate();
    validateClientIsLoggedIn(clientId);
    DateTime now = ofy().getTransactionTime();
    Update command = cloneAndLinkReferences((Update) resourceCommand, now);
    DomainResource existingDomain = loadAndVerifyExistence(DomainResource.class, targetId, now);
    verifyUpdateAllowed(command, existingDomain, now);
    customLogic.afterValidation(
        AfterValidationParameters.newBuilder().setExistingDomain(existingDomain).build());
    HistoryEntry historyEntry = buildHistoryEntry(existingDomain, now);
    DomainResource newDomain = performUpdate(command, existingDomain, now);
    // If the new domain is in the sunrush add grace period and is now publishable to DNS because we
    // have added nameserver or removed holds, we have to convert it to a standard add grace period.
    if (newDomain.shouldPublishToDns()) {
      for (GracePeriod gracePeriod : newDomain.getGracePeriods()) {
        if (gracePeriod.isSunrushAddGracePeriod()) {
          newDomain = convertSunrushAddToAdd(newDomain, gracePeriod, historyEntry, now);
          break; // There can only be one sunrush add grace period.
        }
      }
    }
    validateNewState(newDomain);
    dnsQueue.addDomainRefreshTask(targetId);
    handleExtraFlowLogic(existingDomain, historyEntry, now);
    ImmutableSet.Builder<ImmutableObject> entitiesToSave = new ImmutableSet.Builder<>();
    entitiesToSave.add(newDomain, historyEntry);
    Optional<BillingEvent.OneTime> statusUpdateBillingEvent =
        createBillingEventForStatusUpdates(existingDomain, newDomain, historyEntry, now);
    if (statusUpdateBillingEvent.isPresent()) {
      entitiesToSave.add(statusUpdateBillingEvent.get());
    }
    EntityChanges entityChanges =
        customLogic.beforeSave(
            BeforeSaveParameters.newBuilder()
                .setHistoryEntry(historyEntry)
                .setNewDomain(newDomain)
                .setExistingDomain(existingDomain)
                .setEntityChanges(
                    EntityChanges.newBuilder().setSaves(entitiesToSave.build()).build())
                .build());
    persistEntityChanges(entityChanges);
    return responseBuilder.build();
  }

  /** Fail if the object doesn't exist or was deleted. */
  private void verifyUpdateAllowed(Update command, DomainResource existingDomain, DateTime now)
      throws EppException {
    verifyNoDisallowedStatuses(existingDomain, UPDATE_DISALLOWED_STATUSES);
    verifyOptionalAuthInfo(authInfo, existingDomain);
    AddRemove add = command.getInnerAdd();
    AddRemove remove = command.getInnerRemove();
    if (!isSuperuser) {
      verifyResourceOwnership(clientId, existingDomain);
      verifyClientUpdateNotProhibited(command, existingDomain);
      verifyAllStatusesAreClientSettable(union(add.getStatusValues(), remove.getStatusValues()));
    }
    String tld = existingDomain.getTld();
    checkAllowedAccessToTld(clientId, tld);
    EppCommandOperations commandOperations = TldSpecificLogicProxy.getUpdatePrice(
        Registry.get(tld), targetId, clientId, now, eppInput);

    FeeTransformCommandExtension feeUpdate =
        eppInput.getSingleExtension(FeeUpdateCommandExtension.class);
    // If the fee extension is present, validate it (even if the cost is zero, to check for price
    // mismatches). Don't rely on the the validateFeeChallenge check for feeUpdate nullness, because
    // it throws an error if the name is premium, and we don't want to do that here.
    Money totalCost = commandOperations.getTotalCost();
    if (feeUpdate != null) {
      validateFeeChallenge(targetId, existingDomain.getTld(), now, feeUpdate, totalCost);
    } else if (!totalCost.isZero()) {
      // If it's not present but the cost is not zero, throw an exception.
      throw new FeesRequiredForNonFreeUpdateException();
    }
    verifyNotInPendingDelete(
        add.getContacts(),
        command.getInnerChange().getRegistrant(),
        add.getNameservers());
    validateContactsHaveTypes(add.getContacts());
    validateContactsHaveTypes(remove.getContacts());
    validateRegistrantAllowedOnTld(tld, command.getInnerChange().getRegistrantContactId());
    validateNameserversAllowedOnTld(
        tld, add.getNameserverFullyQualifiedHostNames());
  }

  private HistoryEntry buildHistoryEntry(DomainResource existingDomain, DateTime now) {
    return historyBuilder
        .setType(HistoryEntry.Type.DOMAIN_UPDATE)
        .setModificationTime(now)
        .setParent(Key.create(existingDomain))
        .build();
  }

  private DomainResource performUpdate(Update command, DomainResource domain, DateTime now)
      throws EppException {
    AddRemove add = command.getInnerAdd();
    AddRemove remove = command.getInnerRemove();
    checkSameValuesNotAddedAndRemoved(add.getNameservers(), remove.getNameservers());
    checkSameValuesNotAddedAndRemoved(add.getContacts(), remove.getContacts());
    checkSameValuesNotAddedAndRemoved(add.getStatusValues(), remove.getStatusValues());
    Change change = command.getInnerChange();
    SecDnsUpdateExtension secDnsUpdate = eppInput.getSingleExtension(SecDnsUpdateExtension.class);
    return domain.asBuilder()
        // Handle the secDNS extension.
        .setDsData(secDnsUpdate != null
            ? updateDsData(domain.getDsData(), secDnsUpdate)
            : domain.getDsData())
        .setLastEppUpdateTime(now)
        .setLastEppUpdateClientId(clientId)
        .addStatusValues(add.getStatusValues())
        .removeStatusValues(remove.getStatusValues())
        .addNameservers(add.getNameservers())
        .removeNameservers(remove.getNameservers())
        .addContacts(add.getContacts())
        .removeContacts(remove.getContacts())
        .setRegistrant(firstNonNull(change.getRegistrant(), domain.getRegistrant()))
        .setAuthInfo(firstNonNull(change.getAuthInfo(), domain.getAuthInfo()))
        .build();
  }

  private DomainResource convertSunrushAddToAdd(
      DomainResource newDomain, GracePeriod gracePeriod, HistoryEntry historyEntry, DateTime now) {
    // Cancel the billing event for the sunrush add and replace it with a new billing event.
    BillingEvent.Cancellation billingEventCancellation =
        BillingEvent.Cancellation.forGracePeriod(gracePeriod, historyEntry, targetId);
    BillingEvent.OneTime billingEvent =
        createBillingEventForSunrushConversion(newDomain, historyEntry, gracePeriod, now);
    ofy().save().entities(billingEvent, billingEventCancellation);
    // Modify the grace periods on the domain.
    return newDomain.asBuilder()
        .removeGracePeriod(gracePeriod)
        .addGracePeriod(GracePeriod.forBillingEvent(GracePeriodStatus.ADD, billingEvent))
        .build();
  }

  private BillingEvent.OneTime createBillingEventForSunrushConversion(
      DomainResource existingDomain,
      HistoryEntry historyEntry,
      GracePeriod sunrushAddGracePeriod,
      DateTime now) {
    // Compute the expiration time of the add grace period. We will not allow it to be after the
    // sunrush add grace period expiration time (i.e. you can't get extra add grace period by
    // setting a nameserver).
    DateTime addGracePeriodExpirationTime = earliestOf(
        now.plus(Registry.get(existingDomain.getTld()).getAddGracePeriodLength()),
        sunrushAddGracePeriod.getExpirationTime());
    // Create a new billing event for the add grace period. Note that we do this even if it would
    // occur at the same time as the sunrush add grace period, as the event time will differ
    // between them.
    BillingEvent.OneTime originalAddEvent =
        ofy().load().key(sunrushAddGracePeriod.getOneTimeBillingEvent()).now();
    return new BillingEvent.OneTime.Builder()
        .setReason(Reason.CREATE)
        .setTargetId(targetId)
        .setFlags(originalAddEvent.getFlags())
        .setClientId(sunrushAddGracePeriod.getClientId())
        .setCost(originalAddEvent.getCost())
        .setPeriodYears(originalAddEvent.getPeriodYears())
        .setEventTime(now)
        .setBillingTime(addGracePeriodExpirationTime)
        .setParent(historyEntry)
        .build();
  }

  private void validateNewState(DomainResource newDomain) throws EppException {
    validateNoDuplicateContacts(newDomain.getContacts());
    validateRequiredContactsPresent(newDomain.getRegistrant(), newDomain.getContacts());
    validateDsData(newDomain.getDsData());
    validateNameserversCountForTld(newDomain.getTld(), newDomain.getNameservers().size());
  }

  /** Some status updates cost money. Bill only once no matter how many of them are changed. */
  private Optional<BillingEvent.OneTime> createBillingEventForStatusUpdates(
      DomainResource existingDomain,
      DomainResource newDomain,
      HistoryEntry historyEntry,
      DateTime now) {
    MetadataExtension metadataExtension = eppInput.getSingleExtension(MetadataExtension.class);
    if (metadataExtension != null && metadataExtension.getRequestedByRegistrar()) {
      for (StatusValue statusValue
          : symmetricDifference(existingDomain.getStatusValues(), newDomain.getStatusValues())) {
        if (statusValue.isChargedStatus()) {
          // Only charge once.
          return Optional.of(new BillingEvent.OneTime.Builder()
              .setReason(Reason.SERVER_STATUS)
              .setTargetId(targetId)
              .setClientId(clientId)
              .setCost(Registry.get(existingDomain.getTld()).getServerStatusChangeCost())
              .setEventTime(now)
              .setBillingTime(now)
              .setParent(historyEntry)
              .build());
        }
      }
    }
    return Optional.absent();
  }

  private void handleExtraFlowLogic(
      DomainResource existingDomain, HistoryEntry historyEntry, DateTime now) throws EppException {
    Optional<RegistryExtraFlowLogic> extraFlowLogic =
        RegistryExtraFlowLogicProxy.newInstanceForDomain(existingDomain);
    if (extraFlowLogic.isPresent()) {
      extraFlowLogic.get().performAdditionalDomainUpdateLogic(
          existingDomain, clientId, now, eppInput, historyEntry);
    }
  }
}
