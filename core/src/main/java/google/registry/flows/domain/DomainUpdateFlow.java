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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.ImmutableSortedSet.toImmutableSortedSet;
import static com.google.common.collect.Sets.symmetricDifference;
import static com.google.common.collect.Sets.union;
import static google.registry.dns.DnsUtils.requestDomainDnsRefresh;
import static google.registry.flows.FlowUtils.persistEntityChanges;
import static google.registry.flows.FlowUtils.validateRegistrarIsLoggedIn;
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
import static google.registry.flows.domain.DomainFlowUtils.validateFeesAckedIfPresent;
import static google.registry.flows.domain.DomainFlowUtils.validateNameserversAllowedOnTld;
import static google.registry.flows.domain.DomainFlowUtils.validateNameserversCountForTld;
import static google.registry.flows.domain.DomainFlowUtils.validateNoDuplicateContacts;
import static google.registry.flows.domain.DomainFlowUtils.validateRegistrantAllowedOnTld;
import static google.registry.flows.domain.DomainFlowUtils.validateRequiredContactsPresentIfRequiredForDataset;
import static google.registry.flows.domain.DomainFlowUtils.verifyClientUpdateNotProhibited;
import static google.registry.flows.domain.DomainFlowUtils.verifyNotInPendingDelete;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_UPDATE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.net.InternetDomainName;
import google.registry.config.RegistryConfig;
import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.MutatingFlow;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.flows.custom.DomainUpdateFlowCustomLogic;
import google.registry.flows.custom.DomainUpdateFlowCustomLogic.AfterValidationParameters;
import google.registry.flows.custom.DomainUpdateFlowCustomLogic.BeforeSaveParameters;
import google.registry.flows.custom.EntityChanges;
import google.registry.flows.domain.DomainFlowUtils.MissingRegistrantException;
import google.registry.flows.domain.DomainFlowUtils.NameserversNotSpecifiedForTldWithNameserverAllowListException;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingEvent;
import google.registry.model.contact.Contact;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainCommand.Update;
import google.registry.model.domain.DomainCommand.Update.AddRemove;
import google.registry.model.domain.DomainCommand.Update.Change;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.fee.FeeUpdateCommandExtension;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.domain.secdns.SecDnsUpdateExtension;
import google.registry.model.domain.superuser.DomainUpdateSuperuserExtension;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.poll.PendingActionNotificationResponse.DomainPendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import google.registry.model.tld.Tld;
import google.registry.persistence.VKey;
import java.util.Objects;
import java.util.Optional;
import javax.inject.Inject;
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
 * @error {@link google.registry.flows.EppException.UnimplementedExtensionException}
 * @error {@link google.registry.flows.FlowUtils.NotLoggedInException}
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
 * @error {@link DomainFlowUtils.FeesRequiredForNonFreeOperationException}
 * @error {@link DomainFlowUtils.InvalidDsRecordException}
 * @error {@link DomainFlowUtils.LinkedResourcesDoNotExistException}
 * @error {@link DomainFlowUtils.LinkedResourceInPendingDeleteProhibitsOperationException}
 * @error {@link DomainFlowUtils.MaxSigLifeChangeNotSupportedException}
 * @error {@link DomainFlowUtils.MissingAdminContactException}
 * @error {@link DomainFlowUtils.MissingContactTypeException}
 * @error {@link DomainFlowUtils.MissingTechnicalContactException}
 * @error {@link DomainFlowUtils.MissingRegistrantException}
 * @error {@link DomainFlowUtils.NameserversNotAllowedForTldException}
 * @error {@link NameserversNotSpecifiedForTldWithNameserverAllowListException}
 * @error {@link DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link DomainFlowUtils.RegistrantNotAllowedException}
 * @error {@link DomainFlowUtils.SecDnsAllUsageException}
 * @error {@link DomainFlowUtils.TooManyDsRecordsException}
 * @error {@link DomainFlowUtils.TooManyNameserversException}
 * @error {@link DomainFlowUtils.UrgentAttributeNotSupportedException}
 */
@ReportingSpec(ActivityReportField.DOMAIN_UPDATE)
public final class DomainUpdateFlow implements MutatingFlow {

  /**
   * A list of {@link StatusValue}s that prohibit updates.
   *
   * <p>Superusers can override these statuses and perform updates anyway.
   *
   * <p>Note that CLIENT_UPDATE_PROHIBITED is intentionally not in this list. This is because it
   * requires special checking, since you must be able to clear the status off the object with an
   * update.
   */
  private static final ImmutableSet<StatusValue> UPDATE_DISALLOWED_STATUSES =
      ImmutableSet.of(StatusValue.PENDING_DELETE, StatusValue.SERVER_UPDATE_PROHIBITED);

  @Inject ResourceCommand resourceCommand;
  @Inject ExtensionManager extensionManager;
  @Inject EppInput eppInput;
  @Inject Optional<AuthInfo> authInfo;
  @Inject @RegistrarId String registrarId;
  @Inject @TargetId String targetId;
  @Inject @Superuser boolean isSuperuser;
  @Inject Trid trid;
  @Inject DomainHistory.Builder historyBuilder;
  @Inject EppResponse.Builder responseBuilder;
  @Inject DomainUpdateFlowCustomLogic flowCustomLogic;
  @Inject DomainPricingLogic pricingLogic;
  @Inject DomainUpdateFlow() {}

  @Override
  public EppResponse run() throws EppException {
    extensionManager.register(
        FeeUpdateCommandExtension.class,
        MetadataExtension.class,
        SecDnsUpdateExtension.class,
        DomainUpdateSuperuserExtension.class);
    flowCustomLogic.beforeValidation();
    validateRegistrarIsLoggedIn(registrarId);
    extensionManager.validate();
    DateTime now = tm().getTransactionTime();
    Update command = cloneAndLinkReferences((Update) resourceCommand, now);
    Domain existingDomain = loadAndVerifyExistence(Domain.class, targetId, now);
    verifyUpdateAllowed(command, existingDomain, now);
    flowCustomLogic.afterValidation(
        AfterValidationParameters.newBuilder().setExistingDomain(existingDomain).build());
    Domain newDomain = performUpdate(command, existingDomain, now);
    DomainHistory domainHistory =
        historyBuilder.setType(DOMAIN_UPDATE).setDomain(newDomain).build();
    validateNewState(newDomain);
    if (requiresDnsUpdate(existingDomain, newDomain)) {
      requestDomainDnsRefresh(targetId);
    }
    ImmutableSet.Builder<ImmutableObject> entitiesToSave = new ImmutableSet.Builder<>();
    entitiesToSave.add(newDomain, domainHistory);
    Optional<BillingEvent> statusUpdateBillingEvent =
        createBillingEventForStatusUpdates(existingDomain, newDomain, domainHistory, now);
    statusUpdateBillingEvent.ifPresent(entitiesToSave::add);
    Optional<PollMessage.OneTime> serverStatusUpdatePollMessage =
        createPollMessageForServerStatusUpdates(existingDomain, newDomain, domainHistory, now);
    serverStatusUpdatePollMessage.ifPresent(entitiesToSave::add);
    EntityChanges entityChanges =
        flowCustomLogic.beforeSave(
            BeforeSaveParameters.newBuilder()
                .setHistoryEntry(domainHistory)
                .setNewDomain(newDomain)
                .setExistingDomain(existingDomain)
                .setEntityChanges(
                    EntityChanges.newBuilder().setSaves(entitiesToSave.build()).build())
                .build());
    persistEntityChanges(entityChanges);
    return responseBuilder.build();
  }

  /** Determines if any of the changes to new domain should trigger DNS update. */
  private static boolean requiresDnsUpdate(Domain existingDomain, Domain newDomain) {
    return existingDomain.shouldPublishToDns() != newDomain.shouldPublishToDns()
        || !Objects.equals(newDomain.getDsData(), existingDomain.getDsData())
        || !Objects.equals(newDomain.getNsHosts(), existingDomain.getNsHosts());
  }

  /** Fail if the object doesn't exist or was deleted. */
  private void verifyUpdateAllowed(Update command, Domain existingDomain, DateTime now)
      throws EppException {
    verifyOptionalAuthInfo(authInfo, existingDomain);
    AddRemove add = command.getInnerAdd();
    AddRemove remove = command.getInnerRemove();
    String tldStr = existingDomain.getTld();
    if (!isSuperuser) {
      verifyNoDisallowedStatuses(existingDomain, UPDATE_DISALLOWED_STATUSES);
      verifyResourceOwnership(registrarId, existingDomain);
      verifyClientUpdateNotProhibited(command, existingDomain);
      verifyAllStatusesAreClientSettable(union(add.getStatusValues(), remove.getStatusValues()));
      checkAllowedAccessToTld(registrarId, tldStr);
    }
    Tld tld = Tld.get(tldStr);
    Optional<FeeUpdateCommandExtension> feeUpdate =
        eppInput.getSingleExtension(FeeUpdateCommandExtension.class);
    FeesAndCredits feesAndCredits = pricingLogic.getUpdatePrice(tld, targetId, now);
    validateFeesAckedIfPresent(feeUpdate, feesAndCredits, false);
    verifyNotInPendingDelete(
        add.getContacts(),
        command.getInnerChange().getRegistrant(),
        add.getNameservers());
    validateContactsHaveTypes(add.getContacts());
    validateContactsHaveTypes(remove.getContacts());
    validateRegistrantAllowedOnTld(tldStr, command.getInnerChange().getRegistrantContactId());
    validateNameserversAllowedOnTld(tldStr, add.getNameserverHostNames());
  }

  private Domain performUpdate(Update command, Domain domain, DateTime now) throws EppException {
    AddRemove add = command.getInnerAdd();
    AddRemove remove = command.getInnerRemove();
    checkSameValuesNotAddedAndRemoved(add.getNameservers(), remove.getNameservers());
    checkSameValuesNotAddedAndRemoved(add.getContacts(), remove.getContacts());
    checkSameValuesNotAddedAndRemoved(add.getStatusValues(), remove.getStatusValues());
    Change change = command.getInnerChange();
    Optional<SecDnsUpdateExtension> secDnsUpdate =
        eppInput.getSingleExtension(SecDnsUpdateExtension.class);

    // We have to verify no duplicate contacts _before_ constructing the domain because it is
    // illegal to construct a domain with duplicate contacts.
    Sets.SetView<DesignatedContact> newContacts =
        union(Sets.difference(domain.getContacts(), remove.getContacts()), add.getContacts());
    validateNoDuplicateContacts(newContacts);

    Domain.Builder domainBuilder =
        domain
            .asBuilder()
            // Handle the secDNS extension. As dsData in secDnsUpdate is read from EPP input and
            // does not have domainRepoId set, we create a copy of the existing dsData without
            // domainRepoId for comparison.
            .setDsData(
                secDnsUpdate.isPresent()
                    ? updateDsData(
                        domain.getDsData().stream()
                            .map(DomainDsData::cloneWithoutDomainRepoId)
                            .collect(toImmutableSet()),
                        secDnsUpdate.get())
                    : domain.getDsData())
            .setLastEppUpdateTime(now)
            .setLastEppUpdateRegistrarId(registrarId)
            .addStatusValues(add.getStatusValues())
            .removeStatusValues(remove.getStatusValues())
            .removeContacts(remove.getContacts())
            .addContacts(add.getContacts())
            .setRegistrant(determineUpdatedRegistrant(change, domain))
            .setAuthInfo(Optional.ofNullable(change.getAuthInfo()).orElse(domain.getAuthInfo()));

    if (!add.getNameservers().isEmpty()) {
      domainBuilder.addNameservers(add.getNameservers().stream().collect(toImmutableSet()));
    }
    if (!remove.getNameservers().isEmpty()) {
      domainBuilder.removeNameservers(remove.getNameservers().stream().collect(toImmutableSet()));
    }

    Optional<DomainUpdateSuperuserExtension> superuserExt =
        eppInput.getSingleExtension(DomainUpdateSuperuserExtension.class);
    if (superuserExt.isPresent()) {
      if (superuserExt.get().getAutorenews().isPresent()) {
        boolean autorenews = superuserExt.get().getAutorenews().get();
        domainBuilder.setAutorenewEndTime(
            Optional.ofNullable(autorenews ? null : domain.getRegistrationExpirationTime()));
      }
    }
    return domainBuilder.build();
  }

  private Optional<VKey<Contact>> determineUpdatedRegistrant(Change change, Domain domain)
      throws EppException {
    // During phase 1 of minimum dataset transition, allow registrant to be removed
    if (change.getRegistrantContactId().isPresent()
        && change.getRegistrantContactId().get().isEmpty()) {
      if (RegistryConfig.useMinimumDataset()) {
        return Optional.empty();
      } else {
        throw new MissingRegistrantException();
      }
    }
    return change.getRegistrant().or(domain::getRegistrant);
  }

  /**
   * Checks whether the new state of the domain is valid.
   *
   * <p>Note: Before adding or amending conditions, existing data has to be verified for being
   * compliant with the additions or amendments, otherwise existing data can become invalid and
   * cause Domain update failure.
   */
  private static void validateNewState(Domain newDomain) throws EppException {
    validateRequiredContactsPresentIfRequiredForDataset(
        newDomain.getRegistrant(), newDomain.getContacts());
    validateDsData(newDomain.getDsData());
    validateNameserversCountForTld(
        newDomain.getTld(),
        InternetDomainName.from(newDomain.getDomainName()),
        newDomain.getNameservers().size());
  }

  /** Some status updates cost money. Bill only once no matter how many of them are changed. */
  private Optional<BillingEvent> createBillingEventForStatusUpdates(
      Domain existingDomain, Domain newDomain, DomainHistory historyEntry, DateTime now) {
    Optional<MetadataExtension> metadataExtension =
        eppInput.getSingleExtension(MetadataExtension.class);
    if (metadataExtension.isPresent() && metadataExtension.get().getRequestedByRegistrar()) {
      for (StatusValue statusValue
          : symmetricDifference(existingDomain.getStatusValues(), newDomain.getStatusValues())) {
        if (statusValue.isChargedStatus()) {
          // Only charge once.
          return Optional.of(
              new BillingEvent.Builder()
                  .setReason(Reason.SERVER_STATUS)
                  .setTargetId(targetId)
                  .setRegistrarId(registrarId)
                  .setCost(Tld.get(existingDomain.getTld()).getServerStatusChangeBillingCost())
                  .setEventTime(now)
                  .setBillingTime(now)
                  .setDomainHistory(historyEntry)
                  .build());
        }
      }
    }
    return Optional.empty();
  }

  /** Enqueues a poll message iff a superuser is adding/removing server statuses. */
  private Optional<PollMessage.OneTime> createPollMessageForServerStatusUpdates(
      Domain existingDomain, Domain newDomain, DomainHistory historyEntry, DateTime now) {
    if (registrarId.equals(existingDomain.getPersistedCurrentSponsorRegistrarId())) {
      // Don't send a poll message when a superuser registrar is updating its own domain.
      return Optional.empty();
    }
    ImmutableSortedSet<String> addedServerStatuses =
        Sets.difference(newDomain.getStatusValues(), existingDomain.getStatusValues()).stream()
            .filter(StatusValue::isServerSettable)
            .map(StatusValue::getXmlName)
            .collect(toImmutableSortedSet(Ordering.natural()));
    ImmutableSortedSet<String> removedServerStatuses =
        Sets.difference(existingDomain.getStatusValues(), newDomain.getStatusValues()).stream()
            .filter(StatusValue::isServerSettable)
            .map(StatusValue::getXmlName)
            .collect(toImmutableSortedSet(Ordering.natural()));

    String msg;
    if (!addedServerStatuses.isEmpty() && !removedServerStatuses.isEmpty()) {
      msg =
          String.format(
              "The registry administrator has added the status(es) %s and removed the status(es)"
                  + " %s.",
              addedServerStatuses, removedServerStatuses);
    } else if (!addedServerStatuses.isEmpty()) {
      msg =
          String.format(
              "The registry administrator has added the status(es) %s.", addedServerStatuses);
    } else if (!removedServerStatuses.isEmpty()) {
      msg =
          String.format(
              "The registry administrator has removed the status(es) %s.", removedServerStatuses);
    } else {
      return Optional.empty();
    }

    return Optional.ofNullable(
        new PollMessage.OneTime.Builder()
            .setHistoryEntry(historyEntry)
            .setEventTime(now)
            .setRegistrarId(existingDomain.getCurrentSponsorRegistrarId())
            .setMsg(msg)
            .setResponseData(
                ImmutableList.of(
                    DomainPendingActionNotificationResponse.create(
                        existingDomain.getDomainName(), true, trid, now)))
            .build());
  }
}
