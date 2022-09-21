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

package google.registry.model.billing;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static google.registry.util.CollectionUtils.forceEmptyToNull;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.UnsafeSerializable;
import google.registry.model.annotations.OfyIdAllocation;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.common.TimeOfYear;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.DomainHistory.DomainHistoryId;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.transfer.TransferData.TransferServerApproveEntity;
import google.registry.persistence.VKey;
import google.registry.persistence.WithVKey;
import google.registry.persistence.converter.JodaMoneyType;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.MappedSuperclass;
import javax.persistence.Table;
import org.hibernate.annotations.Columns;
import org.hibernate.annotations.Type;
import org.joda.money.Money;
import org.joda.time.DateTime;

/** A billable event in a domain's lifecycle. */
@MappedSuperclass
public abstract class BillingEvent extends ImmutableObject
    implements Buildable, TransferServerApproveEntity, UnsafeSerializable {

  /** The reason for the bill, which maps 1:1 to skus in go/registry-billing-skus. */
  public enum Reason {
    CREATE(true),
    @Deprecated // DO NOT USE THIS REASON. IT REMAINS BECAUSE OF HISTORICAL DATA. SEE b/31676071.
    ERROR(false),
    FEE_EARLY_ACCESS(true),
    RENEW(true),
    RESTORE(true),
    SERVER_STATUS(false),
    TRANSFER(true);

    private final boolean requiresPeriod;

    Reason(boolean requiresPeriod) {
      this.requiresPeriod = requiresPeriod;
    }

    /**
     * Returns whether billing events with this reason have a period years associated with them.
     *
     * <p>Note that this is an "if an only if" condition.
     */
    public boolean hasPeriodYears() {
      return requiresPeriod;
    }
  }

  /** Set of flags that can be applied to billing events. */
  public enum Flag {
    ALLOCATION,
    ANCHOR_TENANT,
    AUTO_RENEW,
    /** Landrush billing events are historical only and are no longer created. */
    LANDRUSH,
    /**
     * This flag is used on create {@link OneTime} billing events for domains that were reserved.
     *
     * <p>This can happen when allocation tokens are used or superusers override a domain
     * reservation. These cases can need special handling in billing/invoicing. Anchor tenants will
     * never have this flag applied; they will have ANCHOR_TENANT instead.
     */
    RESERVED,
    SUNRISE,
    /**
     * This flag will be added to any {@link OneTime} events that are created via, e.g., an
     * automated process to expand {@link Recurring} events.
     */
    SYNTHETIC
  }

  /**
   * Sets of renewal price behaviors that can be applied to billing recurrences.
   *
   * <p>When a client renews a domain, they could be charged differently, depending on factors such
   * as the client type and the domain itself.
   */
  public enum RenewalPriceBehavior {
    /**
     * This indicates the renewal price is the default price.
     *
     * <p>By default, if the domain is premium, then premium price will be used. Otherwise, the
     * standard price of the TLD will be used.
     */
    DEFAULT,
    /**
     * This indicates the domain will be renewed at standard price even if it's a premium domain.
     *
     * <p>We chose to name this "NONPREMIUM" rather than simply "STANDARD" to avoid confusion
     * between "STANDARD" and "DEFAULT".
     *
     * <p>This price behavior is used with anchor tenants.
     */
    NONPREMIUM,
    /**
     * This indicates that the renewalPrice in {@link Recurring} will be used for domain renewal.
     *
     * <p>The renewalPrice has a non-null value iff the price behavior is set to "SPECIFIED". This
     * behavior is used with internal registrations.
     */
    SPECIFIED
  }

  /** Entity id. */
  @OfyIdAllocation @Id Long id;

  /** The registrar to bill. */
  @Column(name = "registrarId", nullable = false)
  String clientId;

  /** Revision id of the entry in DomainHistory table that ths bill belongs to. */
  @Column(nullable = false)
  Long domainHistoryRevisionId;

  /** ID of the EPP resource that the bill is for. */
  @Column(nullable = false)
  String domainRepoId;

  /** When this event was created. For recurring events, this is also the recurrence start time. */
  @Column(nullable = false)
  DateTime eventTime;

  /** The reason for the bill. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  Reason reason;

  /** The fully qualified domain name of the domain that the bill is for. */
  @Column(name = "domain_name", nullable = false)
  String targetId;

  @Nullable Set<Flag> flags;

  public String getRegistrarId() {
    return clientId;
  }

  public long getDomainHistoryRevisionId() {
    return domainHistoryRevisionId;
  }

  public String getDomainRepoId() {
    return domainRepoId;
  }

  public DateTime getEventTime() {
    return eventTime;
  }

  public long getId() {
    return id;
  }

  public Reason getReason() {
    return reason;
  }

  public String getTargetId() {
    return targetId;
  }

  public DomainHistoryId getDomainHistoryId() {
    return new DomainHistoryId(domainRepoId, domainHistoryRevisionId);
  }

  public ImmutableSet<Flag> getFlags() {
    return nullToEmptyImmutableCopy(flags);
  }

  @Override
  public abstract VKey<? extends BillingEvent> createVKey();

  /** Override Buildable.asBuilder() to give this method stronger typing. */
  @Override
  public abstract Builder<?, ?> asBuilder();

  /** An abstract builder for {@link BillingEvent}. */
  public abstract static class Builder<T extends BillingEvent, B extends Builder<?, ?>>
      extends GenericBuilder<T, B> {

    protected Builder() {}

    protected Builder(T instance) {
      super(instance);
    }

    public B setReason(Reason reason) {
      getInstance().reason = reason;
      return thisCastToDerived();
    }

    public B setId(long id) {
      getInstance().id = id;
      return thisCastToDerived();
    }

    public B setRegistrarId(String registrarId) {
      getInstance().clientId = registrarId;
      return thisCastToDerived();
    }

    public B setEventTime(DateTime eventTime) {
      getInstance().eventTime = eventTime;
      return thisCastToDerived();
    }

    public B setTargetId(String targetId) {
      getInstance().targetId = targetId;
      return thisCastToDerived();
    }

    public B setFlags(ImmutableSet<Flag> flags) {
      getInstance().flags = forceEmptyToNull(checkArgumentNotNull(flags, "flags"));
      return thisCastToDerived();
    }

    public B setDomainHistoryId(DomainHistoryId domainHistoryId) {
      getInstance().domainHistoryRevisionId = domainHistoryId.getId();
      getInstance().domainRepoId = domainHistoryId.getDomainRepoId();
      return thisCastToDerived();
    }

    public B setDomainHistory(DomainHistory domainHistory) {
      return setDomainHistoryId(domainHistory.getDomainHistoryId());
    }

    @Override
    public T build() {
      T instance = getInstance();
      checkNotNull(instance.reason, "Reason must be set");
      checkNotNull(instance.clientId, "Registrar ID must be set");
      checkNotNull(instance.eventTime, "Event time must be set");
      checkNotNull(instance.targetId, "Target ID must be set");
      checkNotNull(instance.domainHistoryRevisionId, "Domain History Revision ID must be set");
      checkNotNull(instance.domainRepoId, "Domain Repo ID must be set");
      return super.build();
    }
  }

  /** A one-time billable event. */
  @Entity(name = "BillingEvent")
  @Table(
      indexes = {
        @Index(columnList = "registrarId"),
        @Index(columnList = "eventTime"),
        @Index(columnList = "billingTime"),
        @Index(columnList = "syntheticCreationTime"),
        @Index(columnList = "domainRepoId"),
        @Index(columnList = "allocationToken"),
        @Index(columnList = "cancellation_matching_billing_recurrence_id")
      })
  @AttributeOverride(name = "id", column = @Column(name = "billing_event_id"))
  @WithVKey(Long.class)
  public static class OneTime extends BillingEvent {

    /** The billable value. */
    @Type(type = JodaMoneyType.TYPE_NAME)
    @Columns(columns = {@Column(name = "cost_amount"), @Column(name = "cost_currency")})
    Money cost;

    /** When the cost should be billed. */
    DateTime billingTime;

    /**
     * The period in years of the action being billed for, if applicable, otherwise null. Used for
     * financial reporting.
     */
    Integer periodYears;

    /**
     * For {@link Flag#SYNTHETIC} events, when this event was persisted to Datastore (i.e. the
     * cursor position at the time the recurrence expansion job was last run). In the event a job
     * needs to be undone, a query on this field will return the complete set of potentially bad
     * events.
     */
    DateTime syntheticCreationTime;

    /**
     * For {@link Flag#SYNTHETIC} events, a {@link VKey} to the {@link Recurring} from which this
     * {@link OneTime} was created. This is needed in order to properly match billing events against
     * {@link Cancellation}s.
     */
    @Column(name = "cancellation_matching_billing_recurrence_id")
    VKey<Recurring> cancellationMatchingBillingEvent;

    /**
     * For {@link Flag#SYNTHETIC} events, the {@link DomainHistory} revision ID of the {@link
     * Recurring} from which this {@link OneTime} was created. This is needed in order to recreate
     * the {@link VKey} when reading from SQL.
     */
    @Column(name = "recurrence_history_revision_id")
    Long recurringEventHistoryRevisionId;

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

    public VKey<Recurring> getCancellationMatchingBillingEvent() {
      return cancellationMatchingBillingEvent;
    }

    public Long getRecurringEventHistoryRevisionId() {
      return recurringEventHistoryRevisionId;
    }

    public Optional<VKey<AllocationToken>> getAllocationToken() {
      return Optional.ofNullable(allocationToken);
    }

    @Override
    public VKey<OneTime> createVKey() {
      return createVKey(getId());
    }

    public static VKey<OneTime> createVKey(long id) {
      return VKey.createSql(OneTime.class, id);
    }

    @Override
    public Builder asBuilder() {
      return new Builder(clone(this));
    }

    /** A builder for {@link OneTime} since it is immutable. */
    public static class Builder extends BillingEvent.Builder<OneTime, Builder> {

      public Builder() {}

      private Builder(OneTime instance) {
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
          Recurring cancellationMatchingBillingEvent) {
        getInstance().cancellationMatchingBillingEvent =
            cancellationMatchingBillingEvent.createVKey();
        getInstance().recurringEventHistoryRevisionId =
            cancellationMatchingBillingEvent.getDomainHistoryRevisionId();
        return this;
      }

      public Builder setAllocationToken(@Nullable VKey<AllocationToken> allocationToken) {
        getInstance().allocationToken = allocationToken;
        return this;
      }

      @Override
      public OneTime build() {
        OneTime instance = getInstance();
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
            instance.getFlags().contains(Flag.SYNTHETIC)
                == (instance.syntheticCreationTime != null),
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

  /**
   * A recurring billable event.
   *
   * <p>Unlike {@link OneTime} events, these do not store an explicit cost, since the cost of the
   * recurring event might change and each time we bill for it, we need to bill at the current cost,
   * not the value that was in use at the time the recurrence was created.
   */
  @Entity(name = "BillingRecurrence")
  @Table(
      indexes = {
        @Index(columnList = "registrarId"),
        @Index(columnList = "eventTime"),
        @Index(columnList = "domainRepoId"),
        @Index(columnList = "recurrenceEndTime"),
        @Index(columnList = "recurrence_time_of_year")
      })
  @AttributeOverride(name = "id", column = @Column(name = "billing_recurrence_id"))
  @WithVKey(Long.class)
  public static class Recurring extends BillingEvent {

    /**
     * The billing event recurs every year between {@link #eventTime} and this time on the [month,
     * day, time] specified in {@link #recurrenceTimeOfYear}.
     */
    DateTime recurrenceEndTime;

    /**
     * The eventTime recurs every year on this [month, day, time] between {@link #eventTime} and
     * {@link #recurrenceEndTime}, inclusive of the start but not of the end.
     *
     * <p>This field is denormalized from {@link #eventTime} to allow for an efficient index, but it
     * always has the same data as that field.
     *
     * <p>Note that this is a recurrence of the event time, not the billing time. The billing time
     * can be calculated by adding the relevant grace period length to this date. The reason for
     * this requirement is that the event time recurs on a {@link org.joda.time.Period} schedule
     * (same day of year, which can be 365 or 366 days later) which is what {@link TimeOfYear} can
     * model, whereas the billing time is a fixed {@link org.joda.time.Duration} later.
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
    @Type(type = JodaMoneyType.TYPE_NAME)
    @Columns(
        columns = {@Column(name = "renewalPriceAmount"), @Column(name = "renewalPriceCurrency")})
    Money renewalPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "renewalPriceBehavior", nullable = false)
    RenewalPriceBehavior renewalPriceBehavior = RenewalPriceBehavior.DEFAULT;

    public DateTime getRecurrenceEndTime() {
      return recurrenceEndTime;
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
    public VKey<Recurring> createVKey() {
      return createVKey(getId());
    }

    public static VKey<Recurring> createVKey(Long id) {
      return VKey.createSql(Recurring.class, id);
    }

    @Override
    public Builder asBuilder() {
      return new Builder(clone(this));
    }

    /** A builder for {@link Recurring} since it is immutable. */
    public static class Builder extends BillingEvent.Builder<Recurring, Builder> {

      public Builder() {}

      private Builder(Recurring instance) {
        super(instance);
      }

      public Builder setRecurrenceEndTime(DateTime recurrenceEndTime) {
        getInstance().recurrenceEndTime = recurrenceEndTime;
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
      public Recurring build() {
        Recurring instance = getInstance();
        checkNotNull(instance.eventTime);
        checkNotNull(instance.reason);
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

  /**
   * An event representing a cancellation of one of the other two billable event types.
   *
   * <p>This is implemented as a separate event rather than a bit on BillingEvent in order to
   * preserve the immutability of billing events.
   */
  @ReportedOn
  @Entity(name = "BillingCancellation")
  @Table(
      indexes = {
        @Index(columnList = "registrarId"),
        @Index(columnList = "eventTime"),
        @Index(columnList = "domainRepoId"),
        @Index(columnList = "billingTime"),
        @Index(columnList = "billing_event_id"),
        @Index(columnList = "billing_recurrence_id")
      })
  @AttributeOverride(name = "id", column = @Column(name = "billing_cancellation_id"))
  @WithVKey(Long.class)
  public static class Cancellation extends BillingEvent {

    /** The billing time of the charge that is being cancelled. */
    DateTime billingTime;

    /**
     * The one-time billing event to cancel, or null for autorenew cancellations.
     *
     * <p>Although the type is {@link VKey} the name "ref" is preserved for historical reasons.
     */
    @Column(name = "billing_event_id")
    VKey<OneTime> refOneTime;

    /**
     * The recurring billing event to cancel, or null for non-autorenew cancellations.
     *
     * <p>Although the type is {@link VKey} the name "ref" is preserved for historical reasons.
     */
    @Column(name = "billing_recurrence_id")
    VKey<Recurring> refRecurring;

    public DateTime getBillingTime() {
      return billingTime;
    }

    public VKey<? extends BillingEvent> getEventKey() {
      return firstNonNull(refOneTime, refRecurring);
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
     * corresponding event time) that will cancel out the provided grace period's billing event,
     * using the supplied targetId and deriving other metadata (clientId, billing time, and the
     * cancellation reason) from the grace period.
     */
    public static Cancellation forGracePeriod(
        GracePeriod gracePeriod,
        DateTime eventTime,
        DomainHistoryId domainHistoryId,
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
      if (gracePeriod.getOneTimeBillingEvent() != null) {
        builder.setOneTimeEventKey(gracePeriod.getOneTimeBillingEvent());
      } else if (gracePeriod.getRecurringBillingEvent() != null) {
        builder.setRecurringEventKey(gracePeriod.getRecurringBillingEvent());
      }
      return builder.build();
    }

    @Override
    public VKey<Cancellation> createVKey() {
      return createVKey(getId());
    }

    public static VKey<Cancellation> createVKey(long id) {
      return VKey.createSql(Cancellation.class, id);
    }

    @Override
    public Builder asBuilder() {
      return new Builder(clone(this));
    }

    /** A builder for {@link Cancellation} since it is immutable. */
    public static class Builder extends BillingEvent.Builder<Cancellation, Builder> {

      public Builder() {}

      private Builder(Cancellation instance) {
        super(instance);
      }

      public Builder setBillingTime(DateTime billingTime) {
        getInstance().billingTime = billingTime;
        return this;
      }

      public Builder setOneTimeEventKey(VKey<OneTime> eventKey) {
        getInstance().refOneTime = eventKey;
        return this;
      }

      public Builder setRecurringEventKey(VKey<Recurring> eventKey) {
        getInstance().refRecurring = eventKey;
        return this;
      }

      @Override
      public Cancellation build() {
        Cancellation instance = getInstance();
        checkNotNull(instance.billingTime, "Must set billing time");
        checkNotNull(instance.reason, "Must set reason");
        checkState(
            (instance.refOneTime == null) != (instance.refRecurring == null),
            "Cancellations must have exactly one billing event key set");
        return super.build();
      }
    }
  }
}
