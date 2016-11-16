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

import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.flows.FlowUtils.validateClientIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.handlePendingTransferOnDelete;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.updateForeignKeyIndexDeletionTime;
import static google.registry.flows.ResourceFlowUtils.verifyNoDisallowedStatuses;
import static google.registry.flows.ResourceFlowUtils.verifyOptionalAuthInfo;
import static google.registry.flows.ResourceFlowUtils.verifyResourceOwnership;
import static google.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static google.registry.flows.domain.DomainFlowUtils.updateAutorenewRecurrenceEndTime;
import static google.registry.flows.domain.DomainFlowUtils.verifyNotInPredelegation;
import static google.registry.model.eppoutput.Result.Code.SUCCESS;
import static google.registry.model.eppoutput.Result.Code.SUCCESS_WITH_ACTION_PENDING;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.pricing.PricingEngineProxy.getDomainRenewCost;
import static google.registry.util.CollectionUtils.nullToEmpty;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.dns.DnsQueue;
import google.registry.flows.EppException;
import google.registry.flows.EppException.AssociationProhibitsOperationException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.ClientId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.ResourceFlowUtils;
import google.registry.flows.SessionMetadata;
import google.registry.flows.TransactionalFlow;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.DomainResource.Builder;
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
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppcommon.ProtocolDefinition.ServiceExtension;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.poll.PendingActionNotificationResponse.DomainPendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.poll.PollMessage.OneTime;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferStatus;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import org.joda.money.Money;
import org.joda.time.DateTime;

/**
 * An EPP flow that deletes a domain.
 *
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException}
 * @error {@link google.registry.flows.exceptions.OnlyToolCanPassMetadataException}
 * @error {@link google.registry.flows.exceptions.ResourceStatusProhibitsOperationException}
 * @error {@link DomainDeleteFlow.DomainToDeleteHasHostsException}
 * @error {@link DomainFlowUtils.BadCommandForRegistryPhaseException}
 * @error {@link DomainFlowUtils.NotAuthorizedForTldException}
 */
public final class DomainDeleteFlow implements TransactionalFlow {

  private static final ImmutableSet<StatusValue> DISALLOWED_STATUSES = ImmutableSet.of(
      StatusValue.LINKED,
      StatusValue.CLIENT_DELETE_PROHIBITED,
      StatusValue.PENDING_DELETE,
      StatusValue.SERVER_DELETE_PROHIBITED);

  @Inject ExtensionManager extensionManager;
  @Inject EppInput eppInput;
  @Inject SessionMetadata sessionMetadata;
  @Inject Optional<AuthInfo> authInfo;
  @Inject @ClientId String clientId;
  @Inject @TargetId String targetId;
  @Inject @Superuser boolean isSuperuser;
  @Inject HistoryEntry.Builder historyBuilder;
  @Inject DnsQueue dnsQueue;
  @Inject Trid trid;
  @Inject EppResponse.Builder responseBuilder;
  @Inject DomainDeleteFlow() {}

  @Override
  public final EppResponse run() throws EppException {
    extensionManager.register(MetadataExtension.class, SecDnsCreateExtension.class);
    extensionManager.validate();
    validateClientIsLoggedIn(clientId);
    DateTime now = ofy().getTransactionTime();
    // Loads the target resource if it exists
    DomainResource existingDomain = loadAndVerifyExistence(DomainResource.class, targetId, now);
    Registry registry = Registry.get(existingDomain.getTld());
    verifyDeleteAllowed(existingDomain, registry, now);
    HistoryEntry historyEntry = buildHistoryEntry(existingDomain, now);
    Builder builder =
        ResourceFlowUtils.<DomainResource, DomainResource.Builder>resolvePendingTransfer(
            existingDomain, TransferStatus.SERVER_CANCELLED, now);
    builder.setDeletionTime(now).setStatusValues(null);
    // If the domain is in the Add Grace Period, we delete it immediately, which is already
    // reflected in the builder we just prepared. Otherwise we give it a PENDING_DELETE status.
    if (!existingDomain.getGracePeriodStatuses().contains(GracePeriodStatus.ADD)) {
      // By default, this should be 30 days of grace, and 5 days of pending delete.
      DateTime deletionTime = now
          .plus(registry.getRedemptionGracePeriodLength())
          .plus(registry.getPendingDeleteLength());
      PollMessage.OneTime deletePollMessage =
          createDeletePollMessage(existingDomain, historyEntry, deletionTime);
      ofy().save().entity(deletePollMessage);
      builder.setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
          .setDeletionTime(deletionTime)
          // Clear out all old grace periods and add REDEMPTION, which does not include a key to a
          // billing event because there isn't one for a domain delete.
          .setGracePeriods(ImmutableSet.of(GracePeriod.createWithoutBillingEvent(
              GracePeriodStatus.REDEMPTION,
              now.plus(registry.getRedemptionGracePeriodLength()),
              clientId)))
          .setDeletePollMessage(Key.create(deletePollMessage));
    }
    handleExtraFlowLogic(existingDomain, historyEntry, now);
    DomainResource newDomain = builder.build();
    updateForeignKeyIndexDeletionTime(newDomain);
    handlePendingTransferOnDelete(existingDomain, newDomain, now, historyEntry);
    // Close the autorenew billing event and poll message. This may delete the poll message.
    updateAutorenewRecurrenceEndTime(existingDomain, now);
    // If there's a pending transfer, the gaining client's autorenew billing
    // event and poll message will already have been deleted in
    // ResourceDeleteFlow since it's listed in serverApproveEntities.
    dnsQueue.addDomainRefreshTask(existingDomain.getFullyQualifiedDomainName());
    // Cancel any grace periods that were still active.
    for (GracePeriod gracePeriod : existingDomain.getGracePeriods()) {
      // No cancellation is written if the grace period was not for a billable event.
      if (gracePeriod.hasBillingEvent()) {
        ofy().save().entity(
            BillingEvent.Cancellation.forGracePeriod(gracePeriod, historyEntry, targetId));
      }
    }
    ofy().save().<ImmutableObject>entities(newDomain, historyEntry);
    return responseBuilder
        .setResultFromCode(
            newDomain.getDeletionTime().isAfter(now) ? SUCCESS_WITH_ACTION_PENDING : SUCCESS)
        .setExtensions(getResponseExtensions(existingDomain, now))
        .build();
  }

  private void verifyDeleteAllowed(DomainResource existingDomain, Registry registry, DateTime now)
      throws EppException {
    verifyNoDisallowedStatuses(existingDomain, DISALLOWED_STATUSES);
    verifyOptionalAuthInfo(authInfo, existingDomain);
    if (!isSuperuser) {
      verifyResourceOwnership(clientId, existingDomain);
      verifyNotInPredelegation(registry, now);
    }
    checkAllowedAccessToTld(clientId, registry.getTld().toString());
    if (!existingDomain.getSubordinateHosts().isEmpty()) {
      throw new DomainToDeleteHasHostsException();
    }
  }

  private HistoryEntry buildHistoryEntry(DomainResource existingResource, DateTime now) {
    return historyBuilder
        .setType(HistoryEntry.Type.DOMAIN_DELETE)
        .setModificationTime(now)
        .setParent(Key.create(existingResource))
        .build();
  }

  private OneTime createDeletePollMessage(
      DomainResource existingResource, HistoryEntry historyEntry, DateTime deletionTime) {
    return new PollMessage.OneTime.Builder()
        .setClientId(existingResource.getCurrentSponsorClientId())
        .setEventTime(deletionTime)
        .setMsg("Domain deleted.")
        .setResponseData(ImmutableList.of(
            DomainPendingActionNotificationResponse.create(
                existingResource.getFullyQualifiedDomainName(), true, trid, deletionTime)))
        .setParent(historyEntry)
        .build();
  }

  private void handleExtraFlowLogic(
      DomainResource existingResource, HistoryEntry historyEntry, DateTime now)
          throws EppException {
    Optional<RegistryExtraFlowLogic> extraFlowLogic =
        RegistryExtraFlowLogicProxy.newInstanceForDomain(existingResource);
    if (extraFlowLogic.isPresent()) {
      extraFlowLogic.get().performAdditionalDomainDeleteLogic(
          existingResource, clientId, now, eppInput, historyEntry);
    }
  }

  @Nullable
  private ImmutableList<FeeTransformResponseExtension> getResponseExtensions(
      DomainResource existingDomain, DateTime now) {
    FeeTransformResponseExtension.Builder feeResponseBuilder = getDeleteResponseBuilder();
    if (feeResponseBuilder == null) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<Credit> creditsBuilder = new ImmutableList.Builder<>();
    for (GracePeriod gracePeriod : existingDomain.getGracePeriods()) {
      if (gracePeriod.hasBillingEvent()) {
        Money cost = getGracePeriodCost(gracePeriod, now);
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

  private Money getGracePeriodCost(GracePeriod gracePeriod, DateTime now) {
    if (gracePeriod.getType() == GracePeriodStatus.AUTO_RENEW) {
      DateTime autoRenewTime =
          ofy().load().key(checkNotNull(gracePeriod.getRecurringBillingEvent())).now()
              .getRecurrenceTimeOfYear()
                  .getLastInstanceBeforeOrAt(now);
      return getDomainRenewCost(targetId, autoRenewTime, 1);
    }
    return ofy().load().key(checkNotNull(gracePeriod.getOneTimeBillingEvent())).now().getCost();
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
