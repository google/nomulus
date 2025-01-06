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

package google.registry.model.domain;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.annotations.VisibleForTesting;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.persistence.VKey;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/**
 * A domain grace period with an expiration time.
 *
 * <p>When a grace period expires, it is lazily removed from the {@link Domain} the next time the
 * resource is loaded from the database.
 */
@Entity
@Table(
    indexes = {
      @Index(columnList = "domainRepoId"),
      @Index(columnList = "billing_event_id"),
      @Index(columnList = "billing_recurrence_id")
    })
public class GracePeriod extends GracePeriodBase {

  @Id
  @Access(AccessType.PROPERTY)
  @Override
  public long getGracePeriodId() {
    return super.getGracePeriodId();
  }

  private static GracePeriod createInternal(
      GracePeriodStatus type,
      String domainRepoId,
      DateTime expirationTime,
      String registrarId,
      @Nullable VKey<BillingEvent> billingEvent,
      @Nullable VKey<BillingRecurrence> billingRecurrence,
      @Nullable Long gracePeriodId) {
    checkArgument(
        billingEvent == null || billingRecurrence == null,
        "A grace period can have at most one billing event");
    checkArgument(
        (billingRecurrence != null) == GracePeriodStatus.AUTO_RENEW.equals(type),
        "BillingRecurrences must be present on (and only on) autorenew grace periods");
    GracePeriod instance = new GracePeriod();
    instance.gracePeriodId =
        gracePeriodId == null ? tm().reTransact(tm()::allocateId) : gracePeriodId;
    instance.type = checkArgumentNotNull(type);
    instance.domainRepoId = checkArgumentNotNull(domainRepoId);
    instance.expirationTime = checkArgumentNotNull(expirationTime);
    instance.clientId = checkArgumentNotNull(registrarId);
    instance.billingEvent = billingEvent;
    instance.billingRecurrence = billingRecurrence;
    return instance;
  }

  /**
   * Creates a GracePeriod for an (optional) OneTime billing event.
   *
   * <p>Normal callers should always use {@link #forBillingEvent} instead, assuming they do not need
   * to avoid loading the BillingEvent from the database. This method should typically be called
   * only from test code to explicitly construct GracePeriods.
   */
  public static GracePeriod create(
      GracePeriodStatus type,
      String domainRepoId,
      DateTime expirationTime,
      String registrarId,
      @Nullable VKey<BillingEvent> billingEventOneTime) {
    return createInternal(
        type, domainRepoId, expirationTime, registrarId, billingEventOneTime, null, null);
  }

  /**
   * Creates a GracePeriod for an (optional) OneTime billing event and a given {@link
   * #gracePeriodId}.
   *
   * <p>Normal callers should always use {@link #forBillingEvent} instead, assuming they do not need
   * to avoid loading the BillingEvent from the database. This method should typically be called
   * only from test code to explicitly construct GracePeriods.
   */
  @VisibleForTesting
  public static GracePeriod create(
      GracePeriodStatus type,
      String domainRepoId,
      DateTime expirationTime,
      String registrarId,
      @Nullable VKey<BillingEvent> billingEventOneTime,
      @Nullable Long gracePeriodId) {
    return createInternal(
        type, domainRepoId, expirationTime, registrarId, billingEventOneTime, null, gracePeriodId);
  }

  public static GracePeriod createFromHistory(GracePeriodHistory history) {
    return createInternal(
        history.type,
        history.domainRepoId,
        history.expirationTime,
        history.clientId,
        history.billingEvent,
        history.billingRecurrence,
        history.gracePeriodId);
  }

  /** Creates a GracePeriod for a Recurrence billing event. */
  public static GracePeriod createForRecurrence(
      GracePeriodStatus type,
      String domainRepoId,
      DateTime expirationTime,
      String registrarId,
      VKey<BillingRecurrence> billingEventRecurrence) {
    checkArgumentNotNull(billingEventRecurrence, "billingEventRecurrence cannot be null");
    return createInternal(
        type, domainRepoId, expirationTime, registrarId, null, billingEventRecurrence, null);
  }

  /** Creates a GracePeriod for a Recurrence billing event and a given {@link #gracePeriodId}. */
  @VisibleForTesting
  public static GracePeriod createForRecurrence(
      GracePeriodStatus type,
      String domainRepoId,
      DateTime expirationTime,
      String registrarId,
      VKey<BillingRecurrence> billingEventRecurrence,
      @Nullable Long gracePeriodId) {
    checkArgumentNotNull(billingEventRecurrence, "billingEventRecurrence cannot be null");
    return createInternal(
        type,
        domainRepoId,
        expirationTime,
        registrarId,
        null,
        billingEventRecurrence,
        gracePeriodId);
  }

  /** Creates a GracePeriod with no billing event. */
  public static GracePeriod createWithoutBillingEvent(
      GracePeriodStatus type, String domainRepoId, DateTime expirationTime, String registrarId) {
    return createInternal(type, domainRepoId, expirationTime, registrarId, null, null, null);
  }

  /** Constructs a GracePeriod of the given type from the provided one-time BillingEvent. */
  public static GracePeriod forBillingEvent(
      GracePeriodStatus type, String domainRepoId, BillingEvent billingEvent) {
    return create(
        type,
        domainRepoId,
        billingEvent.getBillingTime(),
        billingEvent.getRegistrarId(),
        billingEvent.createVKey());
  }

  /** Entity class to represent a historic {@link GracePeriod}. */
  @Entity(name = "GracePeriodHistory")
  @Table(
      indexes = {
        @Index(columnList = "domainRepoId"),
        @Index(columnList = "domainRepoId,domainHistoryRevisionId")
      })
  public static class GracePeriodHistory extends GracePeriodBase {
    @Id Long gracePeriodHistoryRevisionId;

    /** ID for the associated {@link DomainHistory} entity. */
    Long domainHistoryRevisionId;

    @Override
    @Access(AccessType.PROPERTY)
    public long getGracePeriodId() {
      return super.getGracePeriodId();
    }

    public HistoryEntryId getHistoryEntryId() {
      return new HistoryEntryId(getDomainRepoId(), domainHistoryRevisionId);
    }

    static GracePeriodHistory createFrom(long historyRevisionId, GracePeriod gracePeriod) {
      GracePeriodHistory instance = new GracePeriodHistory();
      instance.gracePeriodHistoryRevisionId = tm().reTransact(tm()::allocateId);
      instance.domainHistoryRevisionId = historyRevisionId;
      instance.gracePeriodId = gracePeriod.gracePeriodId;
      instance.type = gracePeriod.type;
      instance.domainRepoId = gracePeriod.domainRepoId;
      instance.expirationTime = gracePeriod.expirationTime;
      instance.clientId = gracePeriod.clientId;
      instance.billingEvent = gracePeriod.billingEvent;
      instance.billingRecurrence = gracePeriod.billingRecurrence;
      return instance;
    }
  }
}
