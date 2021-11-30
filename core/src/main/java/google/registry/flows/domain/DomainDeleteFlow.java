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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static google.registry.flows.FlowUtils.createHistoryKey;
import static google.registry.flows.FlowUtils.persistEntityChanges;
import static google.registry.flows.FlowUtils.validateRegistrarIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyNoDisallowedStatuses;
import static google.registry.flows.ResourceFlowUtils.verifyOptionalAuthInfo;
import static google.registry.flows.ResourceFlowUtils.verifyResourceOwnership;
import static google.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static google.registry.flows.domain.DomainFlowUtils.createCancelingRecords;
import static google.registry.flows.domain.DomainFlowUtils.updateAutorenewRecurrenceEndTime;
import static google.registry.flows.domain.DomainFlowUtils.verifyNotInPredelegation;
import static google.registry.model.ResourceTransferUtils.denyPendingTransfer;
import static google.registry.model.ResourceTransferUtils.handlePendingTransferOnDelete;
import static google.registry.model.ResourceTransferUtils.updateForeignKeyIndexDeletionTime;
import static google.registry.model.eppoutput.Result.Code.SUCCESS;
import static google.registry.model.eppoutput.Result.Code.SUCCESS_WITH_ACTION_PENDING;
import static google.registry.model.reporting.DomainTransactionRecord.TransactionReportField.ADD_FIELDS;
import static google.registry.model.reporting.DomainTransactionRecord.TransactionReportField.RENEW_FIELDS;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_DELETE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.pricing.PricingEngineProxy.getDomainRenewCost;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.CollectionUtils.union;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.googlecode.objectify.Key;
import google.registry.batch.AsyncTaskEnqueuer;
import google.registry.dns.DnsQueue;
import google.registry.flows.EppException;
import google.registry.flows.EppException.AssociationProhibitsOperationException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.SessionMetadata;
import google.registry.flows.TransactionalFlow;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.flows.custom.DomainDeleteFlowCustomLogic;
import google.registry.flows.custom.DomainDeleteFlowCustomLogic.AfterValidationParameters;
import google.registry.flows.custom.DomainDeleteFlowCustomLogic.BeforeResponseParameters;
import google.registry.flows.custom.DomainDeleteFlowCustomLogic.BeforeResponseReturnData;
import google.registry.flows.custom.DomainDeleteFlowCustomLogic.BeforeSaveParameters;
import google.registry.flows.custom.EntityChanges;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.fee.BaseFee.FeeType;
import google.registry.model.domain.fee.Credit;
import google.registry.model.domain.fee.FeeTransformResponseExtension;
import google.registry.model.domain.fee06.FeeDeleteResponseExtensionV06;
import google.registry.model.domain.fee11.FeeDeleteResponseExtensionV11;
import google.registry.model.domain.fee12.FeeDeleteResponseExtensionV12;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.SecDnsCreateExtension;
import google.registry.model.domain.superuser.DomainDeleteSuperuserExtension;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppcommon.ProtocolDefinition.ServiceExtension;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.poll.PendingActionNotificationResponse.DomainPendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import google.registry.model.tld.Registry;
import google.registry.model.tld.Registry.TldType;
import google.registry.model.transfer.TransferStatus;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * An EPP flow that deletes a domain.
 *
 * @error {@link google.registry.flows.EppException.ReadOnlyModeEppException}
 * @error {@link google.registry.flows.EppException.UnimplementedExtensionException}
 * @error {@link google.registry.flows.FlowUtils.NotLoggedInException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException}
 * @error {@link google.registry.flows.exceptions.OnlyToolCanPassMetadataException}
 * @error {@link google.registry.flows.exceptions.ResourceStatusProhibitsOperationException}
 * @error {@link DomainDeleteFlow.DomainToDeleteHasHostsException}
 * @error {@link DomainFlowUtils.BadCommandForRegistryPhaseException}
 * @error {@link DomainFlowUtils.NotAuthorizedForTldException}
 */
@ReportingSpec(ActivityReportField.DOMAIN_DELETE)
public final class DomainDeleteFlow implements TransactionalFlow {

  private static final ImmutableSet<StatusValue> DISALLOWED_STATUSES = ImmutableSet.of(
      StatusValue.CLIENT_DELETE_PROHIBITED,
      StatusValue.PENDING_DELETE,
      StatusValue.SERVER_DELETE_PROHIBITED);

  @Inject ExtensionManager extensionManager;
  @Inject EppInput eppInput;
  @Inject SessionMetadata sessionMetadata;
  @Inject Optional<AuthInfo> authInfo;
  @Inject @RegistrarId String registrarId;
  @Inject @TargetId String targetId;
  @Inject @Superuser boolean isSuperuser;
  @Inject DomainHistory.Builder historyBuilder;
  @Inject DnsQueue dnsQueue;
  @Inject Trid trid;
  @Inject AsyncTaskEnqueuer asyncTaskEnqueuer;
  @Inject EppResponse.Builder responseBuilder;
  @Inject DomainDeleteFlowCustomLogic flowCustomLogic;
  @Inject DomainDeleteFlow() {}

  @Override
  public final EppResponse run() throws EppException {
    extensionManager.register(
        MetadataExtension.class, SecDnsCreateExtension.class, DomainDeleteSuperuserExtension.class);
    flowCustomLogic.beforeValidation();
    extensionManager.validate();
    validateRegistrarIsLoggedIn(registrarId);
    DateTime now = tm().getTransactionTime();
    // Loads the target resource if it exists
    DomainBase existingDomain = loadAndVerifyExistence(DomainBase.class, targetId, now);
    Registry registry = Registry.get(existingDomain.getTld());
    verifyDeleteAllowed(existingDomain, registry, now);
    flowCustomLogic.afterValidation(
        AfterValidationParameters.newBuilder().setExistingDomain(existingDomain).build());
    ImmutableSet.Builder<ImmutableObject> entitiesToSave = new ImmutableSet.Builder<>();
    DomainBase.Builder builder;
    if (existingDomain.getStatusValues().contains(StatusValue.PENDING_TRANSFER)) {
      builder =
          denyPendingTransfer(existingDomain, TransferStatus.SERVER_CANCELLED, now, registrarId)
              .asBuilder();
    } else {
      builder = existingDomain.asBuilder();
    }
    builder.setLastEppUpdateTime(now).setLastEppUpdateRegistrarId(registrarId);
    Duration redemptionGracePeriodLength = registry.getRedemptionGracePeriodLength();
    Duration pendingDeleteLength = registry.getPendingDeleteLength();
    Optional<DomainDeleteSuperuserExtension> domainDeleteSuperuserExtension =
        eppInput.getSingleExtension(DomainDeleteSuperuserExtension.class);
    if (domainDeleteSuperuserExtension.isPresent()) {
      redemptionGracePeriodLength =
          Duration.standardDays(
              domainDeleteSuperuserExtension.get().getRedemptionGracePeriodDays());
      pendingDeleteLength =
          Duration.standardDays(domainDeleteSuperuserExtension.get().getPendingDeleteDays());
    }
    boolean inAddGracePeriod =
        existingDomain.getGracePeriodStatuses().contains(GracePeriodStatus.ADD);
    // If the domain is in the Add Grace Period, we delete it immediately.
    // Otherwise, we give it a PENDING_DELETE status.
    Duration durationUntilDelete =
        inAddGracePeriod
            ? Duration.ZERO
            // By default, this should be 30 days of grace, and 5 days of pending delete.
            : redemptionGracePeriodLength.plus(pendingDeleteLength);
    Key<DomainHistory> domainHistoryKey = createHistoryKey(existingDomain, DomainHistory.class);
    historyBuilder.setId(domainHistoryKey.getId());
    DateTime deletionTime = now.plus(durationUntilDelete);
    if (durationUntilDelete.equals(Duration.ZERO)) {
      builder.setDeletionTime(now).setStatusValues(null);
    } else {
      DateTime redemptionTime = now.plus(redemptionGracePeriodLength);
      asyncTaskEnqueuer.enqueueAsyncResave(
          existingDomain.createVKey(), now, ImmutableSortedSet.of(redemptionTime, deletionTime));
      builder
          .setDeletionTime(deletionTime)
          .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
          // Clear out all old grace periods and add REDEMPTION, which does not include a key to a
          // billing event because there isn't one for a domain delete.
          .setGracePeriods(
              ImmutableSet.of(
                  GracePeriod.createWithoutBillingEvent(
                      GracePeriodStatus.REDEMPTION,
                      existingDomain.getRepoId(),
                      redemptionTime,
                      registrarId)));
      // Note: The expiration time is unchanged, so if it's before the new deletion time, there will
      // be a "phantom autorenew" where the expiration time advances. No poll message will be
      // produced (since we are ending the autorenew recurrences at "now" below) and the billing
      // event that's created will be automatically cancelled by the corresponding Cancellation
      // event that we create below. For now, at least, this is working as intended.
    }

    // Enqueue the deletion poll message if the delete is asynchronous or if requested by a
    // superuser (i.e. the registrar didn't request this delete and thus should be notified even if
    // it is synchronous).
    if (durationUntilDelete.isLongerThan(Duration.ZERO) || isSuperuser) {
      PollMessage.OneTime deletePollMessage =
          createDeletePollMessage(existingDomain, domainHistoryKey, deletionTime);
      entitiesToSave.add(deletePollMessage);
      builder.setDeletePollMessage(deletePollMessage.createVKey());
    }

    // Send a second poll message immediately if the domain is being deleted asynchronously by a
    // registrar other than the sponsoring registrar (which will necessarily be a superuser).
    if (durationUntilDelete.isLongerThan(Duration.ZERO)
        && !registrarId.equals(existingDomain.getPersistedCurrentSponsorRegistrarId())) {
      entitiesToSave.add(
          createImmediateDeletePollMessage(existingDomain, domainHistoryKey, now, deletionTime));
    }

    // Cancel any grace periods that were still active, and set the expiration time accordingly.
    DateTime newExpirationTime = existingDomain.getRegistrationExpirationTime();
    for (GracePeriod gracePeriod : existingDomain.getGracePeriods()) {
      // No cancellation is written if the grace period was not for a billable event.
      if (gracePeriod.hasBillingEvent()) {
        entitiesToSave.add(
            BillingEvent.Cancellation.forGracePeriod(gracePeriod, now, domainHistoryKey, targetId));
        if (gracePeriod.getOneTimeBillingEvent() != null) {
          // Take the amount of amount of registration time being refunded off the expiration time.
          // This can be either add grace periods or renew grace periods.
          BillingEvent.OneTime oneTime = tm().loadByKey(gracePeriod.getOneTimeBillingEvent());
          newExpirationTime = newExpirationTime.minusYears(oneTime.getPeriodYears());
        } else if (gracePeriod.getRecurringBillingEvent() != null) {
          // Take 1 year off the registration if in the autorenew grace period (no need to load the
          // recurring billing event; all autorenews are for 1 year).
          newExpirationTime = newExpirationTime.minusYears(1);
        }
      }
    }
    builder.setRegistrationExpirationTime(newExpirationTime);

    DomainBase newDomain = builder.build();
    DomainHistory domainHistory =
        buildDomainHistory(newDomain, registry, now, durationUntilDelete, inAddGracePeriod);
    updateForeignKeyIndexDeletionTime(newDomain);
    handlePendingTransferOnDelete(existingDomain, newDomain, now, domainHistory);
    // Close the autorenew billing event and poll message. This may delete the poll message.  Store
    // the updated recurring billing event, we'll need it later and can't reload it.
    BillingEvent.Recurring recurringBillingEvent =
        updateAutorenewRecurrenceEndTime(existingDomain, now);
    // If there's a pending transfer, the gaining client's autorenew billing
    // event and poll message will already have been deleted in
    // ResourceDeleteFlow since it's listed in serverApproveEntities.
    dnsQueue.addDomainRefreshTask(existingDomain.getDomainName());

    entitiesToSave.add(newDomain, domainHistory);
    EntityChanges entityChanges =
        flowCustomLogic.beforeSave(
            BeforeSaveParameters.newBuilder()
                .setExistingDomain(existingDomain)
                .setNewDomain(newDomain)
                .setHistoryEntry(domainHistory)
                .setEntityChanges(
                    EntityChanges.newBuilder().setSaves(entitiesToSave.build()).build())
                .build());
    BeforeResponseReturnData responseData =
        flowCustomLogic.beforeResponse(
            BeforeResponseParameters.newBuilder()
                .setResultCode(
                    newDomain.getDeletionTime().isAfter(now)
                        ? SUCCESS_WITH_ACTION_PENDING
                        : SUCCESS)
                .setResponseExtensions(
                    getResponseExtensions(recurringBillingEvent, existingDomain, now))
                .build());
    persistEntityChanges(entityChanges);
    return responseBuilder
        .setResultFromCode(responseData.resultCode())
        .setExtensions(responseData.responseExtensions())
        .build();
  }

  private void verifyDeleteAllowed(DomainBase existingDomain, Registry registry, DateTime now)
      throws EppException {
    verifyNoDisallowedStatuses(existingDomain, DISALLOWED_STATUSES);
    verifyOptionalAuthInfo(authInfo, existingDomain);
    if (!isSuperuser) {
      verifyResourceOwnership(registrarId, existingDomain);
      verifyNotInPredelegation(registry, now);
      checkAllowedAccessToTld(registrarId, registry.getTld().toString());
    }
    if (!existingDomain.getSubordinateHosts().isEmpty()) {
      throw new DomainToDeleteHasHostsException();
    }
  }

  private DomainHistory buildDomainHistory(
      DomainBase domain,
      Registry registry,
      DateTime now,
      Duration durationUntilDelete,
      boolean inAddGracePeriod) {
    // We ignore prober transactions
    if (registry.getTldType() == TldType.REAL) {
      Duration maxGracePeriod = Collections.max(
          ImmutableSet.of(
              registry.getAddGracePeriodLength(),
              registry.getAutoRenewGracePeriodLength(),
              registry.getRenewGracePeriodLength()));
      ImmutableSet<DomainTransactionRecord> cancelledRecords =
          createCancelingRecords(
              domain,
              now,
              maxGracePeriod,
              Sets.immutableEnumSet(Sets.union(ADD_FIELDS, RENEW_FIELDS)));
      historyBuilder.setDomainTransactionRecords(
          union(
              cancelledRecords,
              DomainTransactionRecord.create(
                  domain.getTld(),
                  now.plus(durationUntilDelete),
                  inAddGracePeriod
                      ? TransactionReportField.DELETED_DOMAINS_GRACE
                      : TransactionReportField.DELETED_DOMAINS_NOGRACE,
                  1)));
    }
    return historyBuilder.setType(DOMAIN_DELETE).setDomain(domain).build();
  }

  private PollMessage.OneTime createDeletePollMessage(
      DomainBase existingDomain, Key<DomainHistory> domainHistoryKey, DateTime deletionTime) {
    Optional<MetadataExtension> metadataExtension =
        eppInput.getSingleExtension(MetadataExtension.class);
    boolean hasMetadataMessage =
        metadataExtension.isPresent() && !isNullOrEmpty(metadataExtension.get().getReason());
    String message =
        isSuperuser
            ? (hasMetadataMessage
                ? metadataExtension.get().getReason()
                : "Deleted by registry administrator.")
            : "Domain deleted.";
    return new PollMessage.OneTime.Builder()
        .setRegistrarId(existingDomain.getCurrentSponsorRegistrarId())
        .setEventTime(deletionTime)
        .setMsg(message)
        .setResponseData(
            ImmutableList.of(
                DomainPendingActionNotificationResponse.create(
                    existingDomain.getDomainName(), true, trid, deletionTime)))
        .setParentKey(domainHistoryKey)
        .build();
  }

  private PollMessage.OneTime createImmediateDeletePollMessage(
      DomainBase existingDomain,
      Key<DomainHistory> domainHistoryKey,
      DateTime now,
      DateTime deletionTime) {
    return new PollMessage.OneTime.Builder()
        .setRegistrarId(existingDomain.getPersistedCurrentSponsorRegistrarId())
        .setEventTime(now)
        .setParentKey(domainHistoryKey)
        .setMsg(
            String.format(
                "Domain %s was deleted by registry administrator with final deletion effective: %s",
                existingDomain.getDomainName(), deletionTime))
        .setResponseData(
            ImmutableList.of(
                DomainPendingActionNotificationResponse.create(
                    existingDomain.getDomainName(), true, trid, now)))
        .build();
  }

  @Nullable
  private ImmutableList<FeeTransformResponseExtension> getResponseExtensions(
      BillingEvent.Recurring recurringBillingEvent, DomainBase existingDomain, DateTime now) {
    FeeTransformResponseExtension.Builder feeResponseBuilder = getDeleteResponseBuilder();
    if (feeResponseBuilder == null) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<Credit> creditsBuilder = new ImmutableList.Builder<>();
    for (GracePeriod gracePeriod : existingDomain.getGracePeriods()) {
      if (gracePeriod.hasBillingEvent()) {
        Money cost = getGracePeriodCost(recurringBillingEvent, gracePeriod, now);
        creditsBuilder.add(Credit.create(
            cost.negated().getAmount(), FeeType.CREDIT, gracePeriod.getType().getXmlName()));
        feeResponseBuilder.setCurrency(checkNotNull(cost.getCurrencyUnit()));
      }
    }
    ImmutableList<Credit> credits = creditsBuilder.build();
    if (credits.isEmpty()) {
      return ImmutableList.of();
    }
    return ImmutableList.of(feeResponseBuilder.setCredits(credits).build());
  }

  private Money getGracePeriodCost(
      BillingEvent.Recurring recurringBillingEvent, GracePeriod gracePeriod, DateTime now) {
    if (gracePeriod.getType() == GracePeriodStatus.AUTO_RENEW) {
      // If we updated the autorenew billing event, reuse it.
      DateTime autoRenewTime =
          recurringBillingEvent.getRecurrenceTimeOfYear().getLastInstanceBeforeOrAt(now);
      return getDomainRenewCost(targetId, autoRenewTime, 1);
    }
    return tm().loadByKey(checkNotNull(gracePeriod.getOneTimeBillingEvent())).getCost();
  }

  @Nullable
  private FeeTransformResponseExtension.Builder getDeleteResponseBuilder() {
    Set<String> uris = nullToEmpty(sessionMetadata.getServiceExtensionUris());
    if (uris.contains(ServiceExtension.FEE_0_12.getUri())) {
      return new FeeDeleteResponseExtensionV12.Builder();
    }
    if (uris.contains(ServiceExtension.FEE_0_11.getUri())) {
      return new FeeDeleteResponseExtensionV11.Builder();
    }
    if (uris.contains(ServiceExtension.FEE_0_6.getUri())) {
      return new FeeDeleteResponseExtensionV06.Builder();
    }
    return null;
  }

  /** Domain to be deleted has subordinate hosts. */
  static class DomainToDeleteHasHostsException extends AssociationProhibitsOperationException {
    public DomainToDeleteHasHostsException() {
      super("Domain to be deleted has subordinate hosts");
    }
  }
}
