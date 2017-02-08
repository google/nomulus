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

import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Iterables.getOnlyElement;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainResource;
import google.registry.model.eppcommon.Trid;
import google.registry.model.poll.PendingActionNotificationResponse.DomainPendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferData.Builder;
import google.registry.model.transfer.TransferData.TransferServerApproveEntity;
import google.registry.model.transfer.TransferResponse.DomainTransferResponse;
import google.registry.model.transfer.TransferStatus;
import javax.annotation.Nullable;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Utility logic for facilitating domain transfers.
 */
public class DomainTransferUtils {

  /**
   * Sets up {@link TransferData} for a domain with links to entities for server approval.
   */
  public static TransferData createPendingTransferData(
      TransferData.Builder transferDataBuilder,
      ImmutableSet<TransferServerApproveEntity> serverApproveEntities) {
    ImmutableSet.Builder<Key<? extends TransferServerApproveEntity>> serverApproveEntityKeys =
        new ImmutableSet.Builder<>();
    for (TransferServerApproveEntity entity : serverApproveEntities) {
      serverApproveEntityKeys.add(Key.create(entity));
    }
    return transferDataBuilder
        .setTransferStatus(TransferStatus.PENDING)
        .setServerApproveBillingEvent(Key.create(
            getOnlyElement(filter(serverApproveEntities, BillingEvent.OneTime.class))))
        .setServerApproveAutorenewEvent(Key.create(
            getOnlyElement(filter(serverApproveEntities, BillingEvent.Recurring.class))))
        .setServerApproveAutorenewPollMessage(Key.create(
            getOnlyElement(filter(serverApproveEntities, PollMessage.Autorenew.class))))
        .setServerApproveEntities(serverApproveEntityKeys.build())
        .build();
  }

  /**
   * Returns a set of entities created speculatively in anticipation of a server approval.
   *
   * <p>This set consists of:
   * <ul>
   *    <li>The one-time billing event charging the gaining registrar for the transfer
   *    <li>A cancellation of an autorenew charge for the losing registrar, if the autorenew grace
   *        period will apply at transfer time
   *    <li>A new post-transfer autorenew billing event for the domain (and gaining registrar)
   *    <li>A new post-transfer autorenew poll message for the domain (and gaining registrar)
   *    <li>A poll message for the gaining registrar
   *    <li>A poll message for the losing registrar
   * </ul>
   */
  public static ImmutableSet<TransferServerApproveEntity> createTransferServerApproveEntities(
      DateTime automaticTransferTime,
      DateTime serverApproveNewExpirationTime,
      HistoryEntry historyEntry,
      DomainResource existingDomain,
      String targetId,
      Trid trid,
      String gainingClientId,
      Money transferCost,
      int years,
      DateTime now) {
    // Create a TransferData for the server-approve case to use for the speculative poll messages.
    TransferData serverApproveTransferData =
        createTransferDataBuilder(
            existingDomain, trid, gainingClientId, automaticTransferTime, years, now)
        .setTransferStatus(TransferStatus.SERVER_APPROVED).build();
    Registry registry = Registry.get(existingDomain.getTld());
    return new ImmutableSet.Builder<TransferServerApproveEntity>()
        .add(createTransferBillingEvent(
            automaticTransferTime,
            historyEntry,
            targetId,
            gainingClientId,
            registry,
            transferCost,
            years))
        .addAll(createOptionalAutorenewCancellation(
            automaticTransferTime, historyEntry, targetId, existingDomain)
                .asSet())
        .add(createGainingClientAutorenewEvent(
            serverApproveNewExpirationTime, historyEntry, targetId, gainingClientId))
        .add(createGainingClientAutorenewPollMessage(
            serverApproveNewExpirationTime, historyEntry, targetId, gainingClientId))
        .add(createGainingTransferPollMessage(
            targetId, serverApproveTransferData, serverApproveNewExpirationTime, historyEntry))
        .add(createLosingTransferPollMessage(
            targetId, serverApproveTransferData, serverApproveNewExpirationTime, historyEntry))
        .build();
  }

  /** Create a poll message for the gaining client in a transfer. */
  public static PollMessage createGainingTransferPollMessage(
      String targetId,
      TransferData transferData,
      @Nullable DateTime extendedRegistrationExpirationTime,
      HistoryEntry historyEntry) {
    return new PollMessage.OneTime.Builder()
        .setClientId(transferData.getGainingClientId())
        .setEventTime(transferData.getPendingTransferExpirationTime())
        .setMsg(transferData.getTransferStatus().getMessage())
        .setResponseData(ImmutableList.of(
            createTransferResponse(targetId, transferData, extendedRegistrationExpirationTime),
            DomainPendingActionNotificationResponse.create(
                  targetId,
                  transferData.getTransferStatus().isApproved(),
                  transferData.getTransferRequestTrid(),
                  historyEntry.getModificationTime())))
        .setParent(historyEntry)
        .build();
  }

  /** Create a poll message for the losing client in a transfer. */
  public static PollMessage createLosingTransferPollMessage(
      String targetId,
      TransferData transferData,
      @Nullable DateTime extendedRegistrationExpirationTime,
      HistoryEntry historyEntry) {
    return new PollMessage.OneTime.Builder()
        .setClientId(transferData.getLosingClientId())
        .setEventTime(transferData.getPendingTransferExpirationTime())
        .setMsg(transferData.getTransferStatus().getMessage())
        .setResponseData(ImmutableList.of(
            createTransferResponse(targetId, transferData, extendedRegistrationExpirationTime)))
        .setParent(historyEntry)
        .build();
  }

  /** Create a {@link DomainTransferResponse} off of the info in a {@link TransferData}. */
  static DomainTransferResponse createTransferResponse(
      String targetId,
      TransferData transferData,
      @Nullable DateTime extendedRegistrationExpirationTime) {
    return new DomainTransferResponse.Builder()
        .setFullyQualifiedDomainNameName(targetId)
        .setGainingClientId(transferData.getGainingClientId())
        .setLosingClientId(transferData.getLosingClientId())
        .setPendingTransferExpirationTime(transferData.getPendingTransferExpirationTime())
        .setTransferRequestTime(transferData.getTransferRequestTime())
        .setTransferStatus(transferData.getTransferStatus())
        .setExtendedRegistrationExpirationTime(extendedRegistrationExpirationTime)
        .build();
  }

  private static PollMessage.Autorenew createGainingClientAutorenewPollMessage(
      DateTime serverApproveNewExpirationTime,
      HistoryEntry historyEntry,
      String targetId,
      String gainingClientId) {
    return new PollMessage.Autorenew.Builder()
        .setTargetId(targetId)
        .setClientId(gainingClientId)
        .setEventTime(serverApproveNewExpirationTime)
        .setAutorenewEndTime(END_OF_TIME)
        .setMsg("Domain was auto-renewed.")
        .setParent(historyEntry)
        .build();
  }

  private static BillingEvent.Recurring createGainingClientAutorenewEvent(
      DateTime serverApproveNewExpirationTime,
      HistoryEntry historyEntry,
      String targetId,
      String gainingClientId) {
    return new BillingEvent.Recurring.Builder()
        .setReason(Reason.RENEW)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setTargetId(targetId)
        .setClientId(gainingClientId)
        .setEventTime(serverApproveNewExpirationTime)
        .setRecurrenceEndTime(END_OF_TIME)
        .setParent(historyEntry)
        .build();
  }

  /**
   * Creates an optional autorenew cancellation if one would apply to the server-approved transfer.
   *
   * <p>If there will be an autorenew between now and the automatic transfer time, and if the
   * autorenew grace period length is long enough that the domain will still be within it at the
   * automatic transfer time, then the transfer will subsume the autorenew and we need to write out
   * a cancellation for it.
   */
  // TODO(b/19430703): the above logic is incomplete; it doesn't handle a grace period that started
  //   before the transfer was requested and continues through the automatic transfer time.
  private static Optional<BillingEvent.Cancellation> createOptionalAutorenewCancellation(
      DateTime automaticTransferTime,
      HistoryEntry historyEntry,
      String targetId,
      DomainResource existingDomain) {
    Registry registry = Registry.get(existingDomain.getTld());
    DateTime oldExpirationTime = existingDomain.getRegistrationExpirationTime();
    Duration autoRenewGracePeriodLength = registry.getAutoRenewGracePeriodLength();
    if (automaticTransferTime.isAfter(oldExpirationTime)
        && automaticTransferTime.isBefore(oldExpirationTime.plus(autoRenewGracePeriodLength))) {
      return Optional.of(new BillingEvent.Cancellation.Builder()
          .setReason(Reason.RENEW)
          .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
          .setTargetId(targetId)
          .setClientId(existingDomain.getCurrentSponsorClientId())
          .setEventTime(automaticTransferTime)
          .setBillingTime(existingDomain.getRegistrationExpirationTime()
              .plus(registry.getAutoRenewGracePeriodLength()))
          .setRecurringEventKey(existingDomain.getAutorenewBillingEvent())
          .setParent(historyEntry)
          .build());
    }
    return Optional.absent();
  }

  private static BillingEvent.OneTime createTransferBillingEvent(
      DateTime automaticTransferTime,
      HistoryEntry historyEntry,
      String targetId,
      String gainingClientId,
      Registry registry,
      Money transferCost,
      int years) {
    return new BillingEvent.OneTime.Builder()
        .setReason(Reason.TRANSFER)
        .setTargetId(targetId)
        .setClientId(gainingClientId)
        .setCost(transferCost)
        .setPeriodYears(years)
        .setEventTime(automaticTransferTime)
        .setBillingTime(automaticTransferTime.plus(registry.getTransferGracePeriodLength()))
        .setParent(historyEntry)
        .build();
  }

  private static Builder createTransferDataBuilder(
      DomainResource existingDomain,
      Trid trid,
      String gainingClientId,
      DateTime automaticTransferTime,
      int years,
      DateTime now) {
    return new TransferData.Builder()
        .setTransferRequestTrid(trid)
        .setTransferRequestTime(now)
        .setGainingClientId(gainingClientId)
        .setLosingClientId(existingDomain.getCurrentSponsorClientId())
        .setPendingTransferExpirationTime(automaticTransferTime)
        .setExtendedRegistrationYears(years);
  }
}
