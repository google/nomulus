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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.token.AllocationToken;
import google.registry.persistence.VKey;
import google.registry.persistence.WithVKey;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.money.Money;
import org.joda.time.DateTime;

/** A one-time billable event. */
@Entity
@Table(
    indexes = {
      @Index(columnList = "registrarId"),
      @Index(columnList = "eventTime"),
      @Index(columnList = "billingTime"),
      @Index(columnList = "syntheticCreationTime"),
      @Index(columnList = "domainRepoId"),
      @Index(columnList = "allocationToken"),
      @Index(columnList = "cancellation_matching_billing_recurrence_id"),
      @Index(columnList = "domainRepoId,domainHistoryRevisionId"),
      @Index(columnList = "domainRepoId,recurrenceHistoryRevisionId")
    })
@AttributeOverride(name = "id", column = @Column(name = "billing_event_id"))
@WithVKey(Long.class)
public class BillingEvent extends BillingBase {

  /** The billable value. */
  @AttributeOverride(
      name = "amount",
      // Override Hibernate default (numeric(38,2)) to match real schema definition (numeric(19,2)).
      column = @Column(name = "cost_amount", precision = 19, scale = 2))
  @AttributeOverride(name = "currency", column = @Column(name = "cost_currency"))
  Money cost;

  /** When the cost should be billed. */
  DateTime billingTime;

  /**
   * The period in years of the action being billed for, if applicable, otherwise null. Used for
   * financial reporting.
   */
  Integer periodYears;

  /**
   * For {@link Flag#SYNTHETIC} events, when this event was persisted to the database (i.e. the
   * cursor position at the time the recurrence expansion job was last run). In the event a job
   * needs to be undone, a query on this field will return the complete set of potentially bad
   * events.
   */
  DateTime syntheticCreationTime;

  /**
   * For {@link Flag#SYNTHETIC} events, a {@link VKey} to the {@link BillingRecurrence} from which
   * this {@link google.registry.model.billing.BillingEvent} was created. This is needed in order to
   * properly match billing events against {@link BillingCancellation}s.
   */
  @Column(name = "cancellation_matching_billing_recurrence_id")
  VKey<BillingRecurrence> cancellationMatchingBillingEvent;

  /**
   * For {@link Flag#SYNTHETIC} events, the {@link DomainHistory} revision ID of the {@link
   * BillingRecurrence} from which this {@link google.registry.model.billing.BillingEvent} was
   * created. This is needed in order to recreate the {@link VKey} when reading from SQL.
   */
  @Column(name = "recurrence_history_revision_id")
  Long recurrenceHistoryRevisionId;

  /**
   * The {@link AllocationToken} used in the creation of this event, or null if one was not used.
   */
  @Nullable VKey<AllocationToken> allocationToken;

  public Money getCost() {
    return cost;
  }

  public DateTime getBillingTime() {
    return billingTime;
  }

  public Integer getPeriodYears() {
    return periodYears;
  }

  public DateTime getSyntheticCreationTime() {
    return syntheticCreationTime;
  }

  public VKey<BillingRecurrence> getCancellationMatchingBillingEvent() {
    return cancellationMatchingBillingEvent;
  }

  public Long getRecurrenceHistoryRevisionId() {
    return recurrenceHistoryRevisionId;
  }

  public Optional<VKey<AllocationToken>> getAllocationToken() {
    return Optional.ofNullable(allocationToken);
  }

  @Override
  public VKey<google.registry.model.billing.BillingEvent> createVKey() {
    return createVKey(getId());
  }

  public static VKey<google.registry.model.billing.BillingEvent> createVKey(long id) {
    return VKey.create(google.registry.model.billing.BillingEvent.class, id);
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for {@link google.registry.model.billing.BillingEvent} since it is immutable. */
  public static class Builder
      extends BillingBase.Builder<google.registry.model.billing.BillingEvent, Builder> {

    public Builder() {}

    private Builder(google.registry.model.billing.BillingEvent instance) {
      super(instance);
    }

    public Builder setCost(Money cost) {
      getInstance().cost = cost;
      return this;
    }

    public Builder setPeriodYears(Integer periodYears) {
      checkNotNull(periodYears);
      checkArgument(periodYears > 0);
      getInstance().periodYears = periodYears;
      return this;
    }

    public Builder setBillingTime(DateTime billingTime) {
      getInstance().billingTime = billingTime;
      return this;
    }

    public Builder setSyntheticCreationTime(DateTime syntheticCreationTime) {
      getInstance().syntheticCreationTime = syntheticCreationTime;
      return this;
    }

    public Builder setCancellationMatchingBillingEvent(
        BillingRecurrence cancellationMatchingBillingEvent) {
      getInstance().cancellationMatchingBillingEvent =
          cancellationMatchingBillingEvent.createVKey();
      getInstance().recurrenceHistoryRevisionId =
          cancellationMatchingBillingEvent.getDomainHistoryRevisionId();
      return this;
    }

    public Builder setAllocationToken(@Nullable VKey<AllocationToken> allocationToken) {
      getInstance().allocationToken = allocationToken;
      return this;
    }

    @Override
    public google.registry.model.billing.BillingEvent build() {
      google.registry.model.billing.BillingEvent instance = getInstance();
      checkNotNull(instance.billingTime);
      checkNotNull(instance.cost);
      checkState(!instance.cost.isNegative(), "Costs should be non-negative.");
      // TODO(mcilwain): Enforce this check on all billing events (not just more recent ones)
      //                 post-migration after we add the missing period years values in SQL.
      if (instance.eventTime.isAfter(DateTime.parse("2019-01-01T00:00:00Z"))) {
        checkState(
            instance.reason.hasPeriodYears() == (instance.periodYears != null),
            "Period years must be set if and only if reason is "
                + "CREATE, FEE_EARLY_ACCESS, RENEW, RESTORE or TRANSFER.");
      }
      checkState(
          instance.getFlags().contains(Flag.SYNTHETIC) == (instance.syntheticCreationTime != null),
          "Synthetic creation time must be set if and only if the SYNTHETIC flag is set.");
      checkState(
          instance.getFlags().contains(Flag.SYNTHETIC)
              == (instance.cancellationMatchingBillingEvent != null),
          "Cancellation matching billing event must be set if and only if the SYNTHETIC flag "
              + "is set.");
      return super.build();
    }
  }
}
