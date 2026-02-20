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

import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.util.CollectionUtils.forceEmptyToNull;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.collect.ImmutableSet;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.UnsafeSerializable;
import google.registry.model.annotations.IdAllocation;
import google.registry.model.domain.DomainHistory;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.model.transfer.DomainTransferData.TransferServerApproveEntity;
import google.registry.persistence.VKey;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.util.Set;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/** A billable event in a domain's lifecycle. */
@MappedSuperclass
public abstract class BillingBase extends ImmutableObject
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
     * This flag is used on create {@link BillingEvent} billing events for domains that were
     * reserved.
     *
     * <p>This can happen when allocation tokens are used or superusers override a domain
     * reservation. These cases can need special handling in billing/invoicing. Anchor tenants will
     * never have this flag applied; they will have ANCHOR_TENANT instead.
     */
    RESERVED,
    SUNRISE,
    /**
     * This flag will be added to any {@link BillingEvent} events that are created via, e.g., an
     * automated process to expand {@link BillingRecurrence} events.
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
     * This indicates that the renewalPrice in {@link BillingRecurrence} will be used for domain
     * renewal.
     *
     * <p>The renewalPrice has a non-null value iff the price behavior is set to "SPECIFIED". This
     * behavior is used with internal registrations.
     */
    SPECIFIED
  }

  /** Entity id. */
  @IdAllocation @Id Long id;

  /** The registrar to bill. */
  @Column(name = "registrarId", nullable = false)
  String clientId;

  /** Revision id of the entry in DomainHistory table that ths bill belongs to. */
  @Column(nullable = false)
  Long domainHistoryRevisionId;

  /** ID of the EPP resource that the bill is for. */
  @Column(nullable = false)
  String domainRepoId;

  /** When this event was created. For recurrence events, this is also the recurrence start time. */
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
  @Enumerated(EnumType.STRING)
  Set<Flag> flags;

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

  public HistoryEntryId getHistoryEntryId() {
    return new HistoryEntryId(domainRepoId, domainHistoryRevisionId);
  }

  public ImmutableSet<Flag> getFlags() {
    return nullToEmptyImmutableCopy(flags);
  }

  @Override
  public abstract VKey<? extends BillingBase> createVKey();

  /** Override Buildable.asBuilder() to give this method stronger typing. */
  @Override
  public abstract Builder<?, ?> asBuilder();

  /** An abstract builder for {@link BillingBase}. */
  public abstract static class Builder<T extends BillingBase, B extends Builder<?, ?>>
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

    public B setDomainHistoryId(HistoryEntryId domainHistoryId) {
      getInstance().domainHistoryRevisionId = domainHistoryId.getRevisionId();
      getInstance().domainRepoId = domainHistoryId.getRepoId();
      return thisCastToDerived();
    }

    public B setDomainHistory(DomainHistory domainHistory) {
      return setDomainHistoryId(domainHistory.getHistoryEntryId());
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
}
