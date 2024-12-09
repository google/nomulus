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
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import google.registry.model.common.TimeOfYear;
import google.registry.persistence.VKey;
import google.registry.persistence.WithVKey;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.money.Money;
import org.joda.time.DateTime;

/**
 * A recurring billable event.
 *
 * <p>Unlike {@link BillingEvent} events, these do not store an explicit cost, since the cost of the
 * recurring event might change and each time we bill for it, we need to bill at the current cost,
 * not the value that was in use at the time the recurrence was created.
 */
@Entity
@Table(
    indexes = {
      @Index(columnList = "registrarId"),
      @Index(columnList = "eventTime"),
      @Index(columnList = "domainRepoId"),
      @Index(columnList = "recurrenceEndTime"),
      @Index(columnList = "recurrenceLastExpansion"),
      @Index(columnList = "recurrenceTimeOfYear"),
      @Index(columnList = "domainRepoId,domainHistoryRevisionId")
    })
@AttributeOverride(name = "id", column = @Column(name = "billing_recurrence_id"))
@WithVKey(Long.class)
public class BillingRecurrence extends BillingBase {

  /**
   * The billing event recurs every year between {@link #eventTime} and this time on the [month,
   * day, time] specified in {@link #recurrenceTimeOfYear}.
   */
  DateTime recurrenceEndTime;

  /**
   * The most recent {@link DateTime} when this recurrence was expanded.
   *
   * <p>We only bother checking recurrences for potential expansion if this is at least one year in
   * the past. If it's more recent than that, it means that the recurrence was already expanded too
   * recently to need to be checked again (as domains autorenew each year).
   */
  @Column(nullable = false)
  DateTime recurrenceLastExpansion;

  /**
   * The eventTime recurs every year on this [month, day, time] between {@link #eventTime} and
   * {@link #recurrenceEndTime}, inclusive of the start but not of the end.
   *
   * <p>This field is denormalized from {@link #eventTime} to allow for an efficient index, but it
   * always has the same data as that field.
   *
   * <p>Note that this is a recurrence of the event time, not the billing time. The billing time can
   * be calculated by adding the relevant grace period length to this date. The reason for this
   * requirement is that the event time recurs on a {@link org.joda.time.Period} schedule (same day
   * of year, which can be 365 or 366 days later) which is what {@link TimeOfYear} can model,
   * whereas the billing time is a fixed {@link org.joda.time.Duration} later.
   */
  @Embedded
  @AttributeOverrides(
      @AttributeOverride(name = "timeString", column = @Column(name = "recurrence_time_of_year")))
  TimeOfYear recurrenceTimeOfYear;

  /**
   * The renewal price for domain renewal if and only if it's specified.
   *
   * <p>This price column remains null except when the renewal price behavior of the billing is
   * SPECIFIED. This column is used for internal registrations.
   */
  @Nullable
  @AttributeOverride(
      name = "amount",
      // Override Hibernate default (numeric(38,2)) to match real schema definition (numeric(19,2)).
      column = @Column(name = "renewalPriceAmount", precision = 19, scale = 2))
  @AttributeOverride(name = "currency", column = @Column(name = "renewalPriceCurrency"))
  Money renewalPrice;

  @Enumerated(EnumType.STRING)
  @Column(name = "renewalPriceBehavior", nullable = false)
  RenewalPriceBehavior renewalPriceBehavior = RenewalPriceBehavior.DEFAULT;

  public DateTime getRecurrenceEndTime() {
    return recurrenceEndTime;
  }

  public DateTime getRecurrenceLastExpansion() {
    return recurrenceLastExpansion;
  }

  public TimeOfYear getRecurrenceTimeOfYear() {
    return recurrenceTimeOfYear;
  }

  public RenewalPriceBehavior getRenewalPriceBehavior() {
    return renewalPriceBehavior;
  }

  public Optional<Money> getRenewalPrice() {
    return Optional.ofNullable(renewalPrice);
  }

  @Override
  public VKey<google.registry.model.billing.BillingRecurrence> createVKey() {
    return createVKey(getId());
  }

  public static VKey<google.registry.model.billing.BillingRecurrence> createVKey(Long id) {
    return VKey.create(google.registry.model.billing.BillingRecurrence.class, id);
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /**
   * A builder for {@link google.registry.model.billing.BillingRecurrence} since it is immutable.
   */
  public static class Builder
      extends BillingBase.Builder<google.registry.model.billing.BillingRecurrence, Builder> {

    public Builder() {}

    private Builder(google.registry.model.billing.BillingRecurrence instance) {
      super(instance);
    }

    public Builder setRecurrenceEndTime(DateTime recurrenceEndTime) {
      getInstance().recurrenceEndTime = recurrenceEndTime;
      return this;
    }

    public Builder setRecurrenceLastExpansion(DateTime recurrenceLastExpansion) {
      getInstance().recurrenceLastExpansion = recurrenceLastExpansion;
      return this;
    }

    public Builder setRenewalPriceBehavior(RenewalPriceBehavior renewalPriceBehavior) {
      getInstance().renewalPriceBehavior = renewalPriceBehavior;
      return this;
    }

    public Builder setRenewalPrice(@Nullable Money renewalPrice) {
      getInstance().renewalPrice = renewalPrice;
      return this;
    }

    @Override
    public google.registry.model.billing.BillingRecurrence build() {
      google.registry.model.billing.BillingRecurrence instance = getInstance();
      checkNotNull(instance.eventTime);
      checkNotNull(instance.reason);
      // Don't require recurrenceLastExpansion to be individually set on every new Recurrence.
      // The correct default value if not otherwise set is the event time of the recurrence minus
      // 1 year. This operation is leap-year safe as a billing event created on 2/29 will have its
      // event time on 2/28 next year, and therefore the last expansion time on 2/28 this year. This
      // ensures that it will be expanded on 2/28 next year and included in the February invoice.
      instance.recurrenceLastExpansion =
          Optional.ofNullable(instance.recurrenceLastExpansion)
              .orElse(instance.eventTime.minusYears(1));
      checkArgument(
          instance.renewalPriceBehavior == RenewalPriceBehavior.SPECIFIED
              ^ instance.renewalPrice == null,
          "Renewal price can have a value if and only if the renewal price behavior is"
              + " SPECIFIED");
      instance.recurrenceTimeOfYear = TimeOfYear.fromDateTime(instance.eventTime);
      instance.recurrenceEndTime =
          Optional.ofNullable(instance.recurrenceEndTime).orElse(END_OF_TIME);
      return super.build();
    }
  }
}
