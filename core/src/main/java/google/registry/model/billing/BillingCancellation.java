// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.billing;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableMap;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.persistence.VKey;
import google.registry.persistence.WithVKey;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.joda.time.DateTime;

/**
 * An event representing a cancellation of one of the other two billable event types.
 *
 * <p>This is implemented as a separate event rather than a bit on BillingEvent in order to preserve
 * the immutability of billing events.
 */
@Entity
@Table(
    indexes = {
      @Index(columnList = "registrarId"),
      @Index(columnList = "eventTime"),
      @Index(columnList = "domainRepoId"),
      @Index(columnList = "billingTime"),
      @Index(columnList = "billingEventId"),
      @Index(columnList = "billingRecurrenceId"),
      @Index(columnList = "domainRepoId,domainHistoryRevisionId")
    })
@AttributeOverride(name = "id", column = @Column(name = "billingCancellationId"))
@WithVKey(Long.class)
public class BillingCancellation extends BillingBase {

  /** The billing time of the charge that is being cancelled. */
  DateTime billingTime;

  /** The one-time billing event to cancel, or null for autorenew cancellations. */
  @Column(name = "billing_event_id")
  VKey<BillingEvent> billingEvent;

  /** The Recurrence to cancel, or null for non-autorenew cancellations. */
  @Column(name = "billing_recurrence_id")
  VKey<BillingRecurrence> billingRecurrence;

  public DateTime getBillingTime() {
    return billingTime;
  }

  public VKey<? extends BillingBase> getEventKey() {
    return firstNonNull(billingEvent, billingRecurrence);
  }

  /** The mapping from billable grace period types to originating billing event reasons. */
  static final ImmutableMap<GracePeriodStatus, Reason> GRACE_PERIOD_TO_REASON =
      ImmutableMap.of(
          GracePeriodStatus.ADD, Reason.CREATE,
          GracePeriodStatus.AUTO_RENEW, Reason.RENEW,
          GracePeriodStatus.RENEW, Reason.RENEW,
          GracePeriodStatus.TRANSFER, Reason.TRANSFER);

  /**
   * Creates a cancellation billing event (parented on the provided history key, and with the
   * corresponding event time) that will cancel out the provided grace period's billing event, using
   * the supplied targetId and deriving other metadata (clientId, billing time, and the cancellation
   * reason) from the grace period.
   */
  public static google.registry.model.billing.BillingCancellation forGracePeriod(
      GracePeriod gracePeriod,
      DateTime eventTime,
      HistoryEntryId domainHistoryId,
      String targetId) {
    checkArgument(
        gracePeriod.hasBillingEvent(),
        "Cannot create cancellation for grace period without billing event");
    Builder builder =
        new Builder()
            .setReason(checkNotNull(GRACE_PERIOD_TO_REASON.get(gracePeriod.getType())))
            .setTargetId(targetId)
            .setRegistrarId(gracePeriod.getRegistrarId())
            .setEventTime(eventTime)
            // The charge being cancelled will take place at the grace period's expiration time.
            .setBillingTime(gracePeriod.getExpirationTime())
            .setDomainHistoryId(domainHistoryId);
    // Set the grace period's billing event using the appropriate Cancellation builder method.
    if (gracePeriod.getBillingEvent() != null) {
      builder.setBillingEvent(gracePeriod.getBillingEvent());
    } else if (gracePeriod.getBillingRecurrence() != null) {
      builder.setBillingRecurrence(gracePeriod.getBillingRecurrence());
    }
    return builder.build();
  }

  @Override
  public VKey<google.registry.model.billing.BillingCancellation> createVKey() {
    return createVKey(getId());
  }

  public static VKey<google.registry.model.billing.BillingCancellation> createVKey(long id) {
    return VKey.create(google.registry.model.billing.BillingCancellation.class, id);
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /**
   * A builder for {@link google.registry.model.billing.BillingCancellation} since it is immutable.
   */
  public static class Builder
      extends BillingBase.Builder<google.registry.model.billing.BillingCancellation, Builder> {

    public Builder() {}

    private Builder(google.registry.model.billing.BillingCancellation instance) {
      super(instance);
    }

    public Builder setBillingTime(DateTime billingTime) {
      getInstance().billingTime = billingTime;
      return this;
    }

    public Builder setBillingEvent(VKey<BillingEvent> billingEvent) {
      getInstance().billingEvent = billingEvent;
      return this;
    }

    public Builder setBillingRecurrence(VKey<BillingRecurrence> billingRecurrence) {
      getInstance().billingRecurrence = billingRecurrence;
      return this;
    }

    @Override
    public google.registry.model.billing.BillingCancellation build() {
      google.registry.model.billing.BillingCancellation instance = getInstance();
      checkNotNull(instance.billingTime, "Must set billing time");
      checkNotNull(instance.reason, "Must set reason");
      checkState(
          (instance.billingEvent == null) != (instance.billingRecurrence == null),
          "Cancellations must have exactly one billing event key set");
      return super.build();
    }
  }
}
