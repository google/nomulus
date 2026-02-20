// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.transfer;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.util.CollectionUtils.isNullOrEmpty;
import static google.registry.util.CollectionUtils.nullToEmpty;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.Buildable;
import google.registry.model.billing.BillingCancellation;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Period;
import google.registry.model.domain.Period.Unit;
import google.registry.model.eppcommon.Trid;
import google.registry.model.poll.PollMessage;
import google.registry.persistence.VKey;
import google.registry.util.NullIgnoringCollectionBuilder;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import java.util.Set;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/** Transfer data for domain. */
@Embeddable
public class DomainTransferData extends BaseTransferObject implements Buildable {
  public static final DomainTransferData EMPTY = new DomainTransferData();

  /** The transaction id of the most recent transfer request (or null if there never was one). */
  @Embedded
  @AttributeOverrides({
    @AttributeOverride(
        name = "serverTransactionId",
        column = @Column(name = "transfer_server_txn_id")),
    @AttributeOverride(
        name = "clientTransactionId",
        column = @Column(name = "transfer_client_txn_id"))
  })
  Trid transferRequestTrid;

  @Column(name = "transfer_repo_id")
  String repoId;

  @Column(name = "transfer_history_entry_id")
  Long historyEntryId;

  // The pollMessageId1 and pollMessageId2 are used to store the IDs for gaining and losing poll
  // messages in Cloud SQL.
  //
  // In addition, there may be a third poll message for the autorenew poll message on domain
  // transfer if applicable.
  @Column(name = "transfer_poll_message_id_1")
  Long pollMessageId1;

  @Column(name = "transfer_poll_message_id_2")
  Long pollMessageId2;

  @Column(name = "transfer_poll_message_id_3")
  Long pollMessageId3;

  /**
   * The period to extend the registration upon completion of the transfer.
   *
   * <p>By default, domain transfers are for one year. This can be changed to zero by using the
   * superuser EPP extension.
   */
  @Embedded
  @AttributeOverrides({
    @AttributeOverride(name = "unit", column = @Column(name = "transfer_renew_period_unit")),
    @AttributeOverride(name = "value", column = @Column(name = "transfer_renew_period_value"))
  })
  Period transferPeriod = Period.create(1, Unit.YEARS);

  /**
   * The registration expiration time resulting from the approval - speculative or actual - of the
   * most recent transfer request, applicable for domains only.
   *
   * <p>For pending transfers, this is the expiration time that will take effect under a projected
   * server approval. For approved transfers, this is the actual expiration time of the domain as of
   * the moment of transfer completion. For rejected or cancelled transfers, this field will be
   * reset to null.
   *
   * <p>Note that even when this field is set, it does not necessarily mean that the post-transfer
   * domain has a new expiration time. Superuser transfers may not include a bundled 1 year renewal
   * at all, or even when a renewal is bundled, for a transfer during the autorenew grace period the
   * bundled renewal simply subsumes the recent autorenewal, resulting in the same expiration time.
   */
  // TODO(b/36405140): backfill this field for existing domains to which it should apply.
  @Column(name = "transfer_registration_expiration_time")
  DateTime transferredRegistrationExpirationTime;

  @Column(name = "transfer_billing_cancellation_id")
  public VKey<BillingCancellation> billingCancellationId;

  /**
   * The regular one-time billing event that will be charged for a server-approved transfer.
   *
   * <p>This field should be null if there is not currently a pending transfer or if the object
   * being transferred is not a domain.
   */
  @Column(name = "transfer_billing_event_id")
  VKey<BillingEvent> serverApproveBillingEvent;

  /**
   * The autorenew billing event that should be associated with this resource after the transfer.
   *
   * <p>This field should be null if there is not currently a pending transfer or if the object
   * being transferred is not a domain.
   */
  @Column(name = "transfer_billing_recurrence_id")
  VKey<BillingRecurrence> serverApproveAutorenewEvent;

  /**
   * The autorenew poll message that should be associated with this resource after the transfer.
   *
   * <p>This field should be null if there is not currently a pending transfer or if the object
   * being transferred is not a domain.
   */
  @Column(name = "transfer_autorenew_poll_message_id")
  VKey<PollMessage.Autorenew> serverApproveAutorenewPollMessage;

  /**
   * Autorenew history, which we need to preserve because it's often used in contexts where we
   * haven't loaded the autorenew object.
   */
  @Column(name = "transfer_autorenew_poll_message_history_id")
  Long serverApproveAutorenewPollMessageHistoryId;

  public Period getTransferPeriod() {
    return transferPeriod;
  }

  public Long getHistoryEntryId() {
    return historyEntryId;
  }

  @Nullable
  public Trid getTransferRequestTrid() {
    return transferRequestTrid;
  }

  @Nullable
  public DateTime getTransferredRegistrationExpirationTime() {
    return transferredRegistrationExpirationTime;
  }

  @Nullable
  public VKey<BillingEvent> getServerApproveBillingEvent() {
    return serverApproveBillingEvent;
  }

  @Nullable
  public VKey<BillingRecurrence> getServerApproveAutorenewEvent() {
    return serverApproveAutorenewEvent;
  }

  @Nullable
  public VKey<PollMessage.Autorenew> getServerApproveAutorenewPollMessage() {
    return serverApproveAutorenewPollMessage;
  }

  @Nullable
  public Long getServerApproveAutorenewPollMessageHistoryId() {
    return serverApproveAutorenewPollMessageHistoryId;
  }

  public ImmutableSet<VKey<? extends TransferServerApproveEntity>> getServerApproveEntities() {
    ImmutableSet.Builder<VKey<? extends TransferServerApproveEntity>> builder =
        new ImmutableSet.Builder<>();
    return NullIgnoringCollectionBuilder.create(builder)
        .add(pollMessageId1 != null ? VKey.create(PollMessage.class, pollMessageId1) : null)
        .add(pollMessageId2 != null ? VKey.create(PollMessage.class, pollMessageId2) : null)
        .add(pollMessageId3 != null ? VKey.create(PollMessage.class, pollMessageId3) : null)
        .add(serverApproveBillingEvent)
        .add(serverApproveAutorenewEvent)
        .add(billingCancellationId)
        .getBuilder()
        .build();
  }

  public boolean isEmpty() {
    return EMPTY.equals(this);
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** Maps serverApproveEntities set to the individual fields. */
  @SuppressWarnings("unchecked")
  static void mapBillingCancellationEntityToField(
      Set<VKey<? extends TransferServerApproveEntity>> serverApproveEntities,
      DomainTransferData domainTransferData) {
    if (isNullOrEmpty(serverApproveEntities)) {
      domainTransferData.billingCancellationId = null;
    } else {
      domainTransferData.billingCancellationId =
          (VKey<BillingCancellation>)
              serverApproveEntities.stream()
                  .filter(k -> k.getKind().equals(BillingCancellation.class))
                  .findFirst()
                  .orElse(null);
    }
  }

  /**
   * Returns a fresh Builder populated only with the constant fields of this TransferData, i.e.
   * those that are fixed and unchanging throughout the transfer process.
   *
   * <p>These fields are:
   *
   * <ul>
   *   <li>transferRequestTrid
   *   <li>transferRequestTime
   *   <li>gainingClientId
   *   <li>losingClientId
   *   <li>transferPeriod
   * </ul>
   */
  public Builder copyConstantFieldsToBuilder() {
    return new Builder()
        .setTransferPeriod(transferPeriod)
        .setTransferRequestTrid(transferRequestTrid)
        .setTransferRequestTime(transferRequestTime)
        .setGainingRegistrarId(gainingClientId)
        .setLosingRegistrarId(losingClientId);
  }

  /** Maps serverApproveEntities set to the individual fields. */
  static void mapServerApproveEntitiesToFields(
      Set<VKey<? extends TransferServerApproveEntity>> serverApproveEntities,
      DomainTransferData transferData) {
    if (isNullOrEmpty(serverApproveEntities)) {
      transferData.pollMessageId1 = null;
      transferData.pollMessageId2 = null;
      transferData.pollMessageId3 = null;
      return;
    }
    ImmutableList<Long> sortedPollMessageIds = getSortedPollMessageIds(serverApproveEntities);
    if (!sortedPollMessageIds.isEmpty()) {
      transferData.pollMessageId1 = sortedPollMessageIds.get(0);
    }
    if (sortedPollMessageIds.size() >= 2) {
      transferData.pollMessageId2 = sortedPollMessageIds.get(1);
    }
    if (sortedPollMessageIds.size() >= 3) {
      transferData.pollMessageId3 = sortedPollMessageIds.get(2);
    }
  }

  /**
   * Gets poll message IDs from the given serverApproveEntities and sorts the IDs in natural order.
   */
  private static ImmutableList<Long> getSortedPollMessageIds(
      Set<VKey<? extends TransferServerApproveEntity>> serverApproveEntities) {
    return nullToEmpty(serverApproveEntities).stream()
        .filter(vKey -> PollMessage.class.isAssignableFrom(vKey.getKind()))
        .map(vKey -> (long) vKey.getKey())
        .sorted()
        .collect(toImmutableList());
  }

  /**
   * Marker interface for objects that are written in anticipation of a server approval, and
   * therefore need to be deleted under any other outcome.
   */
  public interface TransferServerApproveEntity {
    VKey<? extends TransferServerApproveEntity> createVKey();
  }

  public static class Builder extends BaseTransferObject.Builder<DomainTransferData, Builder> {
    /** Create a {@link Builder} wrapping a new instance. */
    public Builder() {}

    /** Create a {@link Builder} wrapping the given instance. */
    private Builder(DomainTransferData instance) {
      super(instance);
    }

    @Override
    public DomainTransferData build() {
      if (getInstance().pollMessageId1 != null) {
        checkState(getInstance().repoId != null, "Repo id undefined");
        checkState(getInstance().historyEntryId != null, "History entry undefined");
      }
      return super.build();
    }

    public Builder setTransferRequestTrid(Trid transferRequestTrid) {
      getInstance().transferRequestTrid = transferRequestTrid;
      return this;
    }

    public Builder setTransferPeriod(Period transferPeriod) {
      getInstance().transferPeriod = transferPeriod;
      return this;
    }

    public Builder setTransferredRegistrationExpirationTime(
        DateTime transferredRegistrationExpirationTime) {
      getInstance().transferredRegistrationExpirationTime = transferredRegistrationExpirationTime;
      return this;
    }

    public Builder setServerApproveBillingEvent(VKey<BillingEvent> serverApproveBillingEvent) {
      getInstance().serverApproveBillingEvent = serverApproveBillingEvent;
      return this;
    }

    public Builder setServerApproveAutorenewEvent(
        VKey<BillingRecurrence> serverApproveAutorenewEvent) {
      getInstance().serverApproveAutorenewEvent = serverApproveAutorenewEvent;
      return this;
    }

    public Builder setServerApproveAutorenewPollMessage(
        VKey<PollMessage.Autorenew> serverApproveAutorenewPollMessage) {
      getInstance().serverApproveAutorenewPollMessage = serverApproveAutorenewPollMessage;
      return this;
    }

    public Builder setServerApproveEntities(
        String repoId,
        Long historyId,
        ImmutableSet<VKey<? extends TransferServerApproveEntity>> serverApproveEntities) {
      getInstance().repoId = repoId;
      getInstance().historyEntryId = historyId;
      mapServerApproveEntitiesToFields(serverApproveEntities, getInstance());
      mapBillingCancellationEntityToField(serverApproveEntities, getInstance());
      return this;
    }
  }
}
