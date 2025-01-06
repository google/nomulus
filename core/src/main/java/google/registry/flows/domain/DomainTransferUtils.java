// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.billing.BillingBase.Flag;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingBase.RenewalPriceBehavior;
import google.registry.model.billing.BillingCancellation;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.Period;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.eppcommon.Trid;
import google.registry.model.poll.PendingActionNotificationResponse.DomainPendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.model.tld.Tld;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferData.TransferServerApproveEntity;
import google.registry.model.transfer.TransferResponse.DomainTransferResponse;
import google.registry.model.transfer.TransferStatus;
import google.registry.persistence.VKey;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.money.Money;
import org.joda.time.DateTime;

/**
 * Utility logic for facilitating domain transfers.
 */
public final class DomainTransferUtils {

  /** Sets up {@link TransferData} for a domain with links to entities for server approval. */
  public static DomainTransferData createPendingTransferData(
      String domainRepoId,
      Long historyId,
      DomainTransferData.Builder transferDataBuilder,
      ImmutableSet<TransferServerApproveEntity> serverApproveEntities,
      Period transferPeriod) {
    ImmutableSet.Builder<VKey<? extends TransferServerApproveEntity>> serverApproveEntityKeys =
        new ImmutableSet.Builder<>();
    for (TransferServerApproveEntity entity : serverApproveEntities) {
      serverApproveEntityKeys.add(entity.createVKey());
    }
    if (transferPeriod.getValue() != 0) {
      // Unless superuser sets period to 0, add a transfer billing event.
      transferDataBuilder.setServerApproveBillingEvent(
          serverApproveEntities.stream()
              .filter(BillingEvent.class::isInstance)
              .map(BillingEvent.class::cast)
              .collect(onlyElement())
              .createVKey());
    }
    return transferDataBuilder
        .setTransferStatus(TransferStatus.PENDING)
        .setServerApproveAutorenewEvent(
            serverApproveEntities.stream()
                .filter(BillingRecurrence.class::isInstance)
                .map(BillingRecurrence.class::cast)
                .collect(onlyElement())
                .createVKey())
        .setServerApproveAutorenewPollMessage(
            serverApproveEntities.stream()
                .filter(PollMessage.Autorenew.class::isInstance)
                .map(PollMessage.Autorenew.class::cast)
                .collect(onlyElement())
                .createVKey())
        .setServerApproveEntities(domainRepoId, historyId, serverApproveEntityKeys.build())
        .setTransferPeriod(transferPeriod)
        .build();
  }

  /**
   * Returns a set of entities created speculatively in anticipation of a server approval.
   *
   * <p>This set consists of:
   *
   * <ul>
   *   <li>The one-time billing event charging the gaining registrar for the transfer
   *   <li>A cancellation of an autorenew charge for the losing registrar, if the autorenew grace
   *       period will apply at transfer time
   *   <li>A new post-transfer autorenew billing event for the domain (and gaining registrar)
   *   <li>A new post-transfer autorenew poll message for the domain (and gaining registrar)
   *   <li>A poll message for the gaining registrar
   *   <li>A poll message for the losing registrar
   * </ul>
   */
  public static ImmutableSet<TransferServerApproveEntity> createTransferServerApproveEntities(
      DateTime automaticTransferTime,
      DateTime serverApproveNewExpirationTime,
      HistoryEntryId domainHistoryId,
      Domain existingDomain,
      BillingRecurrence existingBillingRecurrence,
      Trid trid,
      String gainingRegistrarId,
      Optional<Money> transferCost,
      DateTime now) {
    String targetId = existingDomain.getDomainName();
    // Create a TransferData for the server-approve case to use for the speculative poll messages.
    DomainTransferData serverApproveTransferData =
        new DomainTransferData.Builder()
            .setTransferRequestTrid(trid)
            .setTransferRequestTime(now)
            .setGainingRegistrarId(gainingRegistrarId)
            .setLosingRegistrarId(existingDomain.getCurrentSponsorRegistrarId())
            .setPendingTransferExpirationTime(automaticTransferTime)
            .setTransferredRegistrationExpirationTime(serverApproveNewExpirationTime)
            .setTransferStatus(TransferStatus.SERVER_APPROVED)
            .build();
    Tld tld = Tld.get(existingDomain.getTld());
    ImmutableSet.Builder<TransferServerApproveEntity> builder = new ImmutableSet.Builder<>();
    transferCost.ifPresent(
        cost ->
            builder.add(
                createTransferBillingEvent(
                    automaticTransferTime,
                    domainHistoryId,
                    targetId,
                    gainingRegistrarId,
                    tld,
                    cost)));
    createOptionalAutorenewCancellation(
            automaticTransferTime, now, domainHistoryId, targetId, existingDomain, transferCost)
        .ifPresent(builder::add);
    return builder
        .add(
            createGainingClientAutorenewEvent(
                existingDomain.getCurrentBulkToken().isPresent()
                    ? existingBillingRecurrence
                        .asBuilder()
                        .setRenewalPriceBehavior(RenewalPriceBehavior.DEFAULT)
                        .setRenewalPrice(null)
                        .build()
                    : existingBillingRecurrence,
                serverApproveNewExpirationTime,
                domainHistoryId,
                targetId,
                gainingRegistrarId))
        .add(
            createGainingClientAutorenewPollMessage(
                serverApproveNewExpirationTime, domainHistoryId, targetId, gainingRegistrarId))
        .add(
            createGainingTransferPollMessage(
                targetId,
                serverApproveTransferData,
                serverApproveNewExpirationTime,
                now,
                domainHistoryId))
        .add(
            createLosingTransferPollMessage(
                targetId,
                serverApproveTransferData,
                serverApproveNewExpirationTime,
                domainHistoryId))
        .build();
  }

  /** Create a poll message for the gaining client in a transfer. */
  public static PollMessage createGainingTransferPollMessage(
      String targetId,
      TransferData transferData,
      @Nullable DateTime extendedRegistrationExpirationTime,
      DateTime now,
      HistoryEntryId domainHistoryId) {
    return new PollMessage.OneTime.Builder()
        .setRegistrarId(transferData.getGainingRegistrarId())
        .setEventTime(transferData.getPendingTransferExpirationTime())
        .setMsg(transferData.getTransferStatus().getMessage())
        .setResponseData(
            ImmutableList.of(
                createTransferResponse(targetId, transferData, extendedRegistrationExpirationTime),
                DomainPendingActionNotificationResponse.create(
                    targetId,
                    transferData.getTransferStatus().isApproved(),
                    transferData.getTransferRequestTrid(),
                    now)))
        .setDomainHistoryId(domainHistoryId)
        .build();
  }

  /** Create a poll message for the losing client in a transfer. */
  public static PollMessage createLosingTransferPollMessage(
      String targetId,
      TransferData transferData,
      @Nullable DateTime extendedRegistrationExpirationTime,
      HistoryEntryId domainHistoryId) {
    return new PollMessage.OneTime.Builder()
        .setRegistrarId(transferData.getLosingRegistrarId())
        .setEventTime(transferData.getPendingTransferExpirationTime())
        .setMsg(transferData.getTransferStatus().getMessage())
        .setResponseData(
            ImmutableList.of(
                createTransferResponse(targetId, transferData, extendedRegistrationExpirationTime)))
        .setDomainHistoryId(domainHistoryId)
        .build();
  }

  /** Create a {@link DomainTransferResponse} off of the info in a {@link TransferData}. */
  static DomainTransferResponse createTransferResponse(
      String targetId,
      TransferData transferData,
      @Nullable DateTime extendedRegistrationExpirationTime) {
    return new DomainTransferResponse.Builder()
        .setDomainName(targetId)
        .setGainingRegistrarId(transferData.getGainingRegistrarId())
        .setLosingRegistrarId(transferData.getLosingRegistrarId())
        .setPendingTransferExpirationTime(transferData.getPendingTransferExpirationTime())
        .setTransferRequestTime(transferData.getTransferRequestTime())
        .setTransferStatus(transferData.getTransferStatus())
        .setExtendedRegistrationExpirationTime(extendedRegistrationExpirationTime)
        .build();
  }

  private static PollMessage.Autorenew createGainingClientAutorenewPollMessage(
      DateTime serverApproveNewExpirationTime,
      HistoryEntryId domainHistoryId,
      String targetId,
      String gainingRegistrarId) {
    return new PollMessage.Autorenew.Builder()
        .setTargetId(targetId)
        .setRegistrarId(gainingRegistrarId)
        .setEventTime(serverApproveNewExpirationTime)
        .setAutorenewEndTime(END_OF_TIME)
        .setMsg("Domain was auto-renewed.")
        .setDomainHistoryId(domainHistoryId)
        .build();
  }

  private static BillingRecurrence createGainingClientAutorenewEvent(
      BillingRecurrence existingBillingRecurrence,
      DateTime serverApproveNewExpirationTime,
      HistoryEntryId domainHistoryId,
      String targetId,
      String gainingRegistrarId) {
    return new BillingRecurrence.Builder()
        .setReason(Reason.RENEW)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setTargetId(targetId)
        .setRegistrarId(gainingRegistrarId)
        .setEventTime(serverApproveNewExpirationTime)
        .setRecurrenceEndTime(END_OF_TIME)
        .setRenewalPriceBehavior(existingBillingRecurrence.getRenewalPriceBehavior())
        .setRenewalPrice(existingBillingRecurrence.getRenewalPrice().orElse(null))
        .setDomainHistoryId(domainHistoryId)
        .build();
  }

  /**
   * Creates an optional autorenew cancellation if one would apply to the server-approved transfer.
   *
   * <p>If the domain will be in the auto-renew grace period at the automatic transfer time, then
   * the transfer will subsume the autorenew. This means that we "cancel" the 1-year extension of
   * the autorenew before adding the extra transfer year, which results in zero net change in the
   * expiration time. Since the gaining registrar will still be billed for the transfer's 1-year
   * renewal, we must issue a cancellation for the autorenew, so that the losing registrar will not
   * be charged (essentially, the gaining registrar takes on the cost of the year of registration
   * that the autorenew just added). But, if the superuser extension is used to request a transfer
   * without an additional year then the gaining registrar is not charged for the one-year renewal
   * and the losing registrar still needs to be charged for the auto-renew.
   *
   * <p>For details on the policy justification, see b/19430703#comment17 and <a
   * href="https://www.icann.org/news/advisory-2002-06-06-en">this ICANN advisory</a>.
   */
  private static Optional<BillingCancellation> createOptionalAutorenewCancellation(
      DateTime automaticTransferTime,
      DateTime now,
      HistoryEntryId domainHistoryId,
      String targetId,
      Domain existingDomain,
      Optional<Money> transferCost) {
    Domain domainAtTransferTime = existingDomain.cloneProjectedAtTime(automaticTransferTime);
    GracePeriod autorenewGracePeriod =
        getOnlyElement(
            domainAtTransferTime.getGracePeriodsOfType(GracePeriodStatus.AUTO_RENEW), null);
    if (autorenewGracePeriod != null && transferCost.isPresent()) {
      return Optional.of(
          BillingCancellation.forGracePeriod(autorenewGracePeriod, now, domainHistoryId, targetId)
              .asBuilder()
              .setEventTime(automaticTransferTime)
              .build());
    }
    return Optional.empty();
  }

  private static BillingEvent createTransferBillingEvent(
      DateTime automaticTransferTime,
      HistoryEntryId domainHistoryId,
      String targetId,
      String gainingRegistrarId,
      Tld registry,
      Money transferCost) {
    return new BillingEvent.Builder()
        .setReason(Reason.TRANSFER)
        .setTargetId(targetId)
        .setRegistrarId(gainingRegistrarId)
        .setCost(transferCost)
        .setPeriodYears(1)
        .setEventTime(automaticTransferTime)
        .setBillingTime(automaticTransferTime.plus(registry.getTransferGracePeriodLength()))
        .setDomainHistoryId(domainHistoryId)
        .build();
  }

  private DomainTransferUtils() {}
}
