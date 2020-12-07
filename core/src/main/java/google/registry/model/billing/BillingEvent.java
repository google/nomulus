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
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.CollectionUtils.forceEmptyToNull;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Ignore;
import com.googlecode.objectify.annotation.IgnoreSave;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.annotation.OnLoad;
import com.googlecode.objectify.annotation.Parent;
import com.googlecode.objectify.condition.IfNull;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.common.TimeOfYear;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferData.TransferServerApproveEntity;
import google.registry.persistence.VKey;
import google.registry.persistence.WithLongVKey;
import google.registry.schema.replay.DatastoreAndSqlEntity;
import google.registry.schema.replay.DatastoreOnlyEntity;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Embedded;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.MappedSuperclass;
import javax.persistence.PostLoad;
import javax.persistence.Transient;
import org.joda.money.Money;
import org.joda.time.DateTime;

/** A billable event in a domain's lifecycle. */
@MappedSuperclass
public abstract class BillingEvent extends ImmutableObject
    implements Buildable, TransferServerApproveEntity {

  /** The reason for the bill, which maps 1:1 to skus in go/registry-billing-skus. */
  public enum Reason {
    CREATE,
    @Deprecated // TODO(b/31676071): remove this legacy value once old data is cleaned up.
    ERROR,
    FEE_EARLY_ACCESS,
    RENEW,
    RESTORE,
    SERVER_STATUS,
    TRANSFER
  }

  /** Set of flags that can be applied to billing events. */
  public enum Flag {
    ALLOCATION,
    ANCHOR_TENANT,
    AUTO_RENEW,
    /**
     * Landrush billing events are historical only and are no longer created.
     */
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

  /** Entity id. */
  @Id @javax.persistence.Id Long id;

  @Parent @DoNotHydrate @Transient Key<HistoryEntry> parent;

  /** The registrar to bill. */
  @Index
  @Column(name = "registrarId", nullable = false)
  String clientId;

  /** Revision id of the entry in DomainHistory table that ths bill belongs to. */
  @Ignore
  @Column(nullable = false)
  Long domainHistoryRevisionId;

  /** ID of the EPP resource that the bill is for. */
  @Ignore
  @Column(nullable = false)
  String domainRepoId;

  /** When this event was created. For recurring events, this is also the recurrence start time. */
  @Index
  @Column(nullable = false)
  DateTime eventTime;

  /** The reason for the bill. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  Reason reason;

  /** The fully qualified domain name of the domain that the bill is for. */
  @Column(name = "domain_name", nullable = false)
  String targetId;

  @Nullable
  Set<Flag> flags;

  @PostLoad
  void postLoad() {
    parent =
        Key.create(
            Key.create(DomainBase.class, domainRepoId),
            HistoryEntry.class,
            domainHistoryRevisionId);
  }

  @OnLoad
  void onLoad() {
    domainHistoryRevisionId = parent.getId();
    domainRepoId = parent.getParent().getName();
  }

  public String getClientId() {
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

  public Key<HistoryEntry> getParentKey() {
    return parent;
  }

  public ImmutableSet<Flag> getFlags() {
    return nullToEmptyImmutableCopy(flags);
  }

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

    public B setClientId(String clientId) {
      getInstance().clientId = clientId;
      return thisCastToDerived();
    }

    public B setDomainHistoryRevisionId(long domainHistoryRevisionId) {
      getInstance().domainHistoryRevisionId = domainHistoryRevisionId;
      return thisCastToDerived();
    }

    public B setDomainRepoId(String domainRepoId) {
      getInstance().domainRepoId = domainRepoId;
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

    public B setParent(HistoryEntry parent) {
      getInstance().parent = Key.create(parent);
      return thisCastToDerived();
    }

    public B setParent(Key<HistoryEntry> parentKey) {
      getInstance().parent = parentKey;
      return thisCastToDerived();
    }

    @Override
    public T build() {
      T instance = getInstance();
      checkNotNull(instance.reason, "Reason must be set");
      checkNotNull(instance.clientId, "Client ID must be set");
      checkNotNull(instance.eventTime, "Event time must be set");
      checkNotNull(instance.targetId, "Target ID must be set");
      checkNotNull(instance.parent, "Parent must be set");
      checkNotNull(instance.parent.getParent(), "parent.getParent() must be set");
      checkNotNull(
          instance.parent.getParent().getName(), "parent.getParent().getName() must be set");
      instance.domainHistoryRevisionId = instance.parent.getId();
      instance.domainRepoId = instance.parent.getParent().getName();
      return super.build();
    }
  }

  /** A one-time billable event. */
  @ReportedOn
  @Entity
  @javax.persistence.Entity(name = "BillingEvent")
  @javax.persistence.Table(
      indexes = {
        @javax.persistence.Index(columnList = "registrarId"),
        @javax.persistence.Index(columnList = "eventTime"),
        @javax.persistence.Index(columnList = "billingTime"),
        @javax.persistence.Index(columnList = "syntheticCreationTime"),
        @javax.persistence.Index(columnList = "allocationToken")
      })
  @AttributeOverride(name = "id", column = @Column(name = "billing_event_id"))
  @WithLongVKey(compositeKey = true)
  public static class OneTime extends BillingEvent implements DatastoreAndSqlEntity {

    /** The billable value. */
    @AttributeOverrides({
      @AttributeOverride(name = "money.amount", column = @Column(name = "cost_amount")),
      @AttributeOverride(name = "money.currency", column = @Column(name = "cost_currency"))
    })
    Money cost;

    /** When the cost should be billed. */
    @Index
    DateTime billingTime;

    /**
     * The period in years of the action being billed for, if applicable, otherwise null. Used for
     * financial reporting.
     */
    @IgnoreSave(IfNull.class)
    Integer periodYears = null;

    /**
     * For {@link Flag#SYNTHETIC} events, when this event was persisted to Datastore (i.e. the
     * cursor position at the time the recurrence expansion job was last run). In the event a job
     * needs to be undone, a query on this field will return the complete set of potentially bad
     * events.
     */
    @Index
    DateTime syntheticCreationTime;

    /**
     * For {@link Flag#SYNTHETIC} events, a {@link Key} to the {@link BillingEvent} from which this
     * OneTime was created. This is needed in order to properly match billing events against {@link
     * Cancellation}s.
     */
    @Column(name = "cancellation_matching_billing_recurrence_id")
    VKey<Recurring> cancellationMatchingBillingEvent;

    /**
     * The {@link AllocationToken} used in the creation of this event, or null if one was not used.
     */
    @Index
    @Nullable
    VKey<AllocationToken> allocationToken;

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

    public VKey<? extends BillingEvent> getCancellationMatchingBillingEvent() {
      return cancellationMatchingBillingEvent;
    }

    public Optional<VKey<AllocationToken>> getAllocationToken() {
      return Optional.ofNullable(allocationToken);
    }

    @Override
    public VKey<OneTime> createVKey() {
      return VKey.create(OneTime.class, getId(), Key.create(this));
    }

    public static VKey<OneTime> createVKey(Key<OneTime> key) {
      return VKey.create(OneTime.class, key.getId(), key);
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
          VKey<Recurring> cancellationMatchingBillingEvent) {
        getInstance().cancellationMatchingBillingEvent = cancellationMatchingBillingEvent;
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
        ImmutableSet<Reason> reasonsWithPeriods =
            Sets.immutableEnumSet(
                Reason.CREATE,
                Reason.FEE_EARLY_ACCESS,
                Reason.RENEW,
                Reason.RESTORE,
                Reason.TRANSFER);
        checkState(
            reasonsWithPeriods.contains(instance.reason) == (instance.periodYears != null),
            "Period years must be set if and only if reason is "
                + "CREATE, FEE_EARLY_ACCESS, RENEW, RESTORE or TRANSFER.");
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
   * recurring event might change and each time we bill for it we need to bill at the current cost,
   * not the value that was in use at the time the recurrence was created.
   */
  @ReportedOn
  @Entity
  @javax.persistence.Entity(name = "BillingRecurrence")
  @javax.persistence.Table(
      indexes = {
        @javax.persistence.Index(columnList = "registrarId"),
        @javax.persistence.Index(columnList = "eventTime"),
        @javax.persistence.Index(columnList = "recurrenceEndTime"),
        @javax.persistence.Index(columnList = "recurrence_time_of_year")
      })
  @AttributeOverride(name = "id", column = @Column(name = "billing_recurrence_id"))
  @WithLongVKey(compositeKey = true)
  public static class Recurring extends BillingEvent implements DatastoreAndSqlEntity {

    /**
     * The billing event recurs every year between {@link #eventTime} and this time on the
     * [month, day, time] specified in {@link #recurrenceTimeOfYear}.
     */
    @Index
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
    @Index
    @Embedded
    @AttributeOverrides({
      @AttributeOverride(name = "timeString", column = @Column(name = "recurrence_time_of_year"))
    })
    TimeOfYear recurrenceTimeOfYear;

    public DateTime getRecurrenceEndTime() {
      return recurrenceEndTime;
    }

    public TimeOfYear getRecurrenceTimeOfYear() {
      return recurrenceTimeOfYear;
    }

    @Override
    public VKey<Recurring> createVKey() {
      return VKey.create(Recurring.class, getId(), Key.create(this));
    }

    public static VKey<Recurring> createVKey(Key<Recurring> key) {
      return VKey.create(Recurring.class, key.getId(), key);
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

      @Override
      public Recurring build() {
        Recurring instance = getInstance();
        checkNotNull(instance.eventTime);
        checkNotNull(instance.reason);
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
  @Entity
  @javax.persistence.Entity(name = "BillingCancellation")
  @javax.persistence.Table(
      indexes = {
        @javax.persistence.Index(columnList = "registrarId"),
        @javax.persistence.Index(columnList = "eventTime"),
        @javax.persistence.Index(columnList = "billingTime")
      })
  @AttributeOverride(name = "id", column = @Column(name = "billing_cancellation_id"))
  @WithLongVKey(compositeKey = true)
  public static class Cancellation extends BillingEvent implements DatastoreAndSqlEntity {

    /** The billing time of the charge that is being cancelled. */
    @Index
    DateTime billingTime;

    /**
     * The one-time billing event to cancel, or null for autorenew cancellations.
     *
     * <p>Although the type is {@link Key} the name "ref" is preserved for historical reasons.
     */
    @IgnoreSave(IfNull.class)
    @Column(name = "billing_event_id")
    VKey<BillingEvent.OneTime> refOneTime = null;

    /**
     * The recurring billing event to cancel, or null for non-autorenew cancellations.
     *
     * <p>Although the type is {@link Key} the name "ref" is preserved for historical reasons.
     */
    @IgnoreSave(IfNull.class)
    @Column(name = "billing_recurrence_id")
    VKey<BillingEvent.Recurring> refRecurring = null;

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
     * Creates a cancellation billing event (parented on the provided history entry, and with the
     * history entry's event time) that will cancel out the provided grace period's billing event,
     * using the supplied targetId and deriving other metadata (clientId, billing time, and the
     * cancellation reason) from the grace period.
     */
    public static BillingEvent.Cancellation forGracePeriod(
        GracePeriod gracePeriod, HistoryEntry historyEntry, String targetId) {
      checkArgument(gracePeriod.hasBillingEvent(),
          "Cannot create cancellation for grace period without billing event");
      BillingEvent.Cancellation.Builder builder = new BillingEvent.Cancellation.Builder()
          .setReason(checkNotNull(GRACE_PERIOD_TO_REASON.get(gracePeriod.getType())))
          .setTargetId(targetId)
          .setClientId(gracePeriod.getClientId())
          .setEventTime(historyEntry.getModificationTime())
          // The charge being cancelled will take place at the grace period's expiration time.
          .setBillingTime(gracePeriod.getExpirationTime())
          .setParent(historyEntry);
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
      return VKey.create(Cancellation.class, getId(), Key.create(this));
    }

    public static VKey<Cancellation> createVKey(Key<Cancellation> key) {
      return VKey.create(Cancellation.class, key.getId(), key);
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

      public Builder setOneTimeEventKey(VKey<BillingEvent.OneTime> eventKey) {
        getInstance().refOneTime = eventKey;
        return this;
      }

      public Builder setRecurringEventKey(VKey<BillingEvent.Recurring> eventKey) {
        getInstance().refRecurring = eventKey;
        return this;
      }

      @Override
      public Cancellation build() {
        Cancellation instance = getInstance();
        checkNotNull(instance.billingTime, "Must set billing time");
        checkNotNull(instance.reason, "Must set reason");
        checkState((instance.refOneTime == null) != (instance.refRecurring == null),
            "Cancellations must have exactly one billing event key set");
        return super.build();
      }
    }
  }

  /** An event representing a modification of an existing one-time billing event. */
  @ReportedOn
  @Entity
  @WithLongVKey(compositeKey = true)
  public static class Modification extends BillingEvent implements DatastoreOnlyEntity {

    /** The change in cost that should be applied to the original billing event. */
    Money cost;

    /** The one-time billing event to modify. */
    Key<BillingEvent.OneTime> eventRef;

    /**
     * Description of the modification (and presumably why it was issued). This text may appear as a
     * line item on an invoice or report about such modifications.
     */
    String description;

    public Money getCost() {
      return cost;
    }

    public Key<BillingEvent.OneTime> getEventKey() {
      return eventRef;
    }

    public String getDescription() {
      return description;
    }

    @Override
    public Builder asBuilder() {
      return new Builder(clone(this));
    }

    @Override
    public VKey<Modification> createVKey() {
      return VKey.create(Modification.class, getId(), Key.create(this));
    }

    public static VKey<Modification> createVKey(Key<Modification> key) {
      return VKey.create(Modification.class, key.getId(), key);
    }

    /**
     * Create a new Modification billing event which is a refund of the given OneTime billing event
     * and that is parented off the given HistoryEntry.
     *
     * <p>Note that this method may appear to be unused most of the time, but it is kept around
     * because it is needed by one-off scrap tools that need to make billing adjustments.
     */
    public static Modification createRefundFor(
        OneTime billingEvent, HistoryEntry historyEntry, String description) {
      return new Builder()
          .setClientId(billingEvent.getClientId())
          .setFlags(billingEvent.getFlags())
          .setReason(billingEvent.getReason())
          .setTargetId(billingEvent.getTargetId())
          .setEventKey(Key.create(billingEvent))
          .setEventTime(historyEntry.getModificationTime())
          .setDescription(description)
          .setCost(billingEvent.getCost().negated())
          .setParent(historyEntry)
          .build();
    }

    /** A builder for {@link Modification} since it is immutable. */
    public static class Builder extends BillingEvent.Builder<Modification, Builder> {

      public Builder() {}

      private Builder(Modification instance) {
        super(instance);
      }

      public Builder setCost(Money cost) {
        getInstance().cost = cost;
        return this;
      }

      public Builder setEventKey(Key<BillingEvent.OneTime> eventKey) {
        getInstance().eventRef = eventKey;
        return this;
      }

      public Builder setDescription(String description) {
        getInstance().description = description;
        return this;
      }

      @Override
      public Modification build() {
        Modification instance = getInstance();
        checkNotNull(instance.reason);
        checkNotNull(instance.eventRef);
        BillingEvent.OneTime billingEvent = ofy().load().key(instance.eventRef).now();
        checkArgument(Objects.equals(
            instance.cost.getCurrencyUnit(),
            billingEvent.cost.getCurrencyUnit()),
            "Referenced billing event is in a different currency");
        return super.build();
      }
    }
  }
}
