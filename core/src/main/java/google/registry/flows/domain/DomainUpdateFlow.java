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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
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
import static google.registry.flows.domain.DomainFlowUtils.validateFeesAckedIfPresent;
import static google.registry.flows.domain.DomainFlowUtils.validateNameserversAllowedOnTld;
import static google.registry.flows.domain.DomainFlowUtils.validateNameserversCountForTld;
import static google.registry.flows.domain.DomainFlowUtils.validateNoDuplicateContacts;
import static google.registry.flows.domain.DomainFlowUtils.validateRegistrantAllowedOnTld;
import static google.registry.flows.domain.DomainFlowUtils.validateRequiredContactsPresent;
import static google.registry.flows.domain.DomainFlowUtils.verifyClientUpdateNotProhibited;
import static google.registry.flows.domain.DomainFlowUtils.verifyNotInPendingDelete;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_UPDATE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableSet;
import com.google.common.net.InternetDomainName;
import google.registry.dns.DnsQueue;
import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.ClientId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.TransactionalFlow;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.flows.custom.DomainUpdateFlowCustomLogic;
import google.registry.flows.custom.DomainUpdateFlowCustomLogic.AfterValidationParameters;
import google.registry.flows.custom.DomainUpdateFlowCustomLogic.BeforeSaveParameters;
import google.registry.flows.custom.EntityChanges;
import google.registry.flows.domain.DomainFlowUtils.MissingRegistrantException;
import google.registry.flows.domain.DomainFlowUtils.NameserversNotSpecifiedForTldWithNameserverAllowListException;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainCommand.Update;
import google.registry.model.domain.DomainCommand.Update.AddRemove;
import google.registry.model.domain.DomainCommand.Update.Change;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.fee.FeeUpdateCommandExtension;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.domain.secdns.SecDnsUpdateExtension;
import google.registry.model.domain.superuser.DomainUpdateSuperuserExtension;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
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
public final class DomainUpdateFlow implements TransactionalFlow {

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
  @Inject @ClientId String clientId;
  @Inject @TargetId String targetId;
  @Inject @Superuser boolean isSuperuser;
  @Inject DomainHistory.Builder historyBuilder;
  @Inject DnsQueue dnsQueue;
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
    extensionManager.validate();
    validateClientIsLoggedIn(clientId);
    DateTime now = tm().getTransactionTime();
    Update command = cloneAndLinkReferences((Update) resourceCommand, now);
    DomainBase existingDomain = loadAndVerifyExistence(DomainBase.class, targetId, now);
    verifyUpdateAllowed(command, existingDomain, now);
    flowCustomLogic.afterValidation(
        AfterValidationParameters.newBuilder().setExistingDomain(existingDomain).build());
    DomainBase newDomain = performUpdate(command, existingDomain, now);
    DomainHistory domainHistory =
        historyBuilder.setType(DOMAIN_UPDATE).setDomain(newDomain).build();
    validateNewState(newDomain);
    dnsQueue.addDomainRefreshTask(targetId);
    ImmutableSet.Builder<ImmutableObject> entitiesToSave = new ImmutableSet.Builder<>();
    entitiesToSave.add(newDomain, domainHistory);
    Optional<BillingEvent.OneTime> statusUpdateBillingEvent =
        createBillingEventForStatusUpdates(existingDomain, newDomain, domainHistory, now);
    statusUpdateBillingEvent.ifPresent(entitiesToSave::add);
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

  /** Fail if the object doesn't exist or was deleted. */
  private void verifyUpdateAllowed(Update command, DomainBase existingDomain, DateTime now)
      throws EppException {
    verifyOptionalAuthInfo(authInfo, existingDomain);
    AddRemove add = command.getInnerAdd();
    AddRemove remove = command.getInnerRemove();
    String tld = existingDomain.getTld();
    if (!isSuperuser) {
      verifyNoDisallowedStatuses(existingDomain, UPDATE_DISALLOWED_STATUSES);
      verifyResourceOwnership(clientId, existingDomain);
      verifyClientUpdateNotProhibited(command, existingDomain);
      verifyAllStatusesAreClientSettable(union(add.getStatusValues(), remove.getStatusValues()));
      checkAllowedAccessToTld(clientId, tld);
    }
    Registry registry = Registry.get(tld);
    Optional<FeeUpdateCommandExtension> feeUpdate =
        eppInput.getSingleExtension(FeeUpdateCommandExtension.class);
    FeesAndCredits feesAndCredits = pricingLogic.getUpdatePrice(registry, targetId, now);
    validateFeesAckedIfPresent(feeUpdate, feesAndCredits);
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

  private DomainBase performUpdate(Update command, DomainBase domain, DateTime now)
      throws EppException {
    AddRemove add = command.getInnerAdd();
    AddRemove remove = command.getInnerRemove();
    checkSameValuesNotAddedAndRemoved(add.getNameservers(), remove.getNameservers());
    checkSameValuesNotAddedAndRemoved(add.getContacts(), remove.getContacts());
    checkSameValuesNotAddedAndRemoved(add.getStatusValues(), remove.getStatusValues());
    Change change = command.getInnerChange();
    validateRegistrantIsntBeingRemoved(change);
    Optional<SecDnsUpdateExtension> secDnsUpdate =
        eppInput.getSingleExtension(SecDnsUpdateExtension.class);
    DomainBase.Builder domainBuilder =
        domain
            .asBuilder()
            // Handle the secDNS extension. As dsData in secDnsUpdate is read from EPP input and
            // does not have domainRepoId set, we create a copy of the existing dsData without
            // domainRepoId for comparison.
            .setDsData(
                secDnsUpdate.isPresent()
                    ? updateDsData(
                        domain.getDsData().stream()
                            .map(DelegationSignerData::cloneWithoutDomainRepoId)
                            .collect(toImmutableSet()),
                        secDnsUpdate.get())
                    : domain.getDsData())
            .setLastEppUpdateTime(now)
            .setLastEppUpdateClientId(clientId)
            .addStatusValues(add.getStatusValues())
            .removeStatusValues(remove.getStatusValues())
            .addNameservers(add.getNameservers().stream().collect(toImmutableSet()))
            .removeNameservers(remove.getNameservers().stream().collect(toImmutableSet()))
            .addContacts(add.getContacts())
            .removeContacts(remove.getContacts())
            .setRegistrant(firstNonNull(change.getRegistrant(), domain.getRegistrant()))
            .setAuthInfo(firstNonNull(change.getAuthInfo(), domain.getAuthInfo()));
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

  private void validateRegistrantIsntBeingRemoved(Change change) throws EppException {
    if (change.getRegistrantContactId() != null && change.getRegistrantContactId().isEmpty()) {
      throw new MissingRegistrantException();
    }
  }

  private void validateNewState(DomainBase newDomain) throws EppException {
    validateNoDuplicateContacts(newDomain.getContacts());
    validateRequiredContactsPresent(newDomain.getRegistrant(), newDomain.getContacts());
    validateDsData(newDomain.getDsData());
    validateNameserversCountForTld(
        newDomain.getTld(),
        InternetDomainName.from(newDomain.getDomainName()),
        newDomain.getNameservers().size());
  }

  /** Some status updates cost money. Bill only once no matter how many of them are changed. */
  private Optional<BillingEvent.OneTime> createBillingEventForStatusUpdates(
      DomainBase existingDomain, DomainBase newDomain, DomainHistory historyEntry, DateTime now) {
    Optional<MetadataExtension> metadataExtension =
        eppInput.getSingleExtension(MetadataExtension.class);
    if (metadataExtension.isPresent() && metadataExtension.get().getRequestedByRegistrar()) {
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
    return Optional.empty();
  }
}
