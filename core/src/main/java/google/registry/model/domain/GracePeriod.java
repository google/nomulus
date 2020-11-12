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

package google.registry.model.domain;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.OnLoad;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.ofy.ObjectifyService;
import google.registry.persistence.VKey;
import google.registry.schema.replay.DatastoreAndSqlEntity;
import javax.annotation.Nullable;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import org.joda.time.DateTime;

/**
 * A domain grace period with an expiration time.
 *
 * <p>When a grace period expires, it is lazily removed from the {@link DomainBase} the next time
 * the resource is loaded from Datastore.
 */
@Embed
@Entity
@Table(indexes = @Index(columnList = "domainRepoId"))
public class GracePeriod extends GracePeriodBase implements DatastoreAndSqlEntity {

  @Id
  @Access(AccessType.PROPERTY)
  @Override
  public long getGracePeriodId() {
    return super.getGracePeriodId();
  }

  // TODO(b/169873747): Remove this method after explicitly re-saving all domain entities.
  @OnLoad
  void onLoad() {
    if (gracePeriodId == null) {
      gracePeriodId = ObjectifyService.allocateId();
    }
  }

  private static GracePeriod createInternal(
      GracePeriodStatus type,
      String domainRepoId,
      DateTime expirationTime,
      String clientId,
      @Nullable VKey<BillingEvent.OneTime> billingEventOneTime,
      @Nullable VKey<BillingEvent.Recurring> billingEventRecurring,
      @Nullable Long gracePeriodId) {
    checkArgument((billingEventOneTime == null) || (billingEventRecurring == null),
        "A grace period can have at most one billing event");
    checkArgument(
        (billingEventRecurring != null) == GracePeriodStatus.AUTO_RENEW.equals(type),
        "Recurring billing events must be present on (and only on) autorenew grace periods");
    GracePeriod instance = new GracePeriod();
    instance.gracePeriodId = gracePeriodId == null ? ObjectifyService.allocateId() : gracePeriodId;
    instance.type = checkArgumentNotNull(type);
    instance.domainRepoId = checkArgumentNotNull(domainRepoId);
    instance.expirationTime = checkArgumentNotNull(expirationTime);
    instance.clientId = checkArgumentNotNull(clientId);
    instance.billingEventOneTime = billingEventOneTime;
    instance.billingEventOneTimeHistoryId = DomainBase.getHistoryId(billingEventOneTime);
    instance.billingEventRecurring = billingEventRecurring;
    instance.billingEventRecurringHistoryId = DomainBase.getHistoryId(billingEventRecurring);
    return instance;
  }

  /**
   * Creates a GracePeriod for an (optional) OneTime billing event.
   *
   * <p>Normal callers should always use {@link #forBillingEvent} instead, assuming they do not need
   * to avoid loading the BillingEvent from Datastore. This method should typically be called only
   * from test code to explicitly construct GracePeriods.
   */
  public static GracePeriod create(
      GracePeriodStatus type,
      String domainRepoId,
      DateTime expirationTime,
      String clientId,
      @Nullable VKey<BillingEvent.OneTime> billingEventOneTime) {
    return createInternal(
        type, domainRepoId, expirationTime, clientId, billingEventOneTime, null, null);
  }

  /**
   * Creates a GracePeriod for an (optional) OneTime billing event and a given {@link
   * #gracePeriodId}.
   *
   * <p>Normal callers should always use {@link #forBillingEvent} instead, assuming they do not need
   * to avoid loading the BillingEvent from Datastore. This method should typically be called only
   * from test code to explicitly construct GracePeriods.
   */
  @VisibleForTesting
  public static GracePeriod create(
      GracePeriodStatus type,
      String domainRepoId,
      DateTime expirationTime,
      String clientId,
      @Nullable VKey<BillingEvent.OneTime> billingEventOneTime,
      @Nullable Long gracePeriodId) {
    return createInternal(
        type, domainRepoId, expirationTime, clientId, billingEventOneTime, null, gracePeriodId);
  }

  /** Creates a GracePeriod for a Recurring billing event. */
  public static GracePeriod createForRecurring(
      GracePeriodStatus type,
      String domainRepoId,
      DateTime expirationTime,
      String clientId,
      VKey<Recurring> billingEventRecurring) {
    checkArgumentNotNull(billingEventRecurring, "billingEventRecurring cannot be null");
    return createInternal(
        type, domainRepoId, expirationTime, clientId, null, billingEventRecurring, null);
  }

  /** Creates a GracePeriod for a Recurring billing event and a given {@link #gracePeriodId}. */
  @VisibleForTesting
  public static GracePeriod createForRecurring(
      GracePeriodStatus type,
      String domainRepoId,
      DateTime expirationTime,
      String clientId,
      VKey<Recurring> billingEventRecurring,
      @Nullable Long gracePeriodId) {
    checkArgumentNotNull(billingEventRecurring, "billingEventRecurring cannot be null");
    return createInternal(
        type, domainRepoId, expirationTime, clientId, null, billingEventRecurring, gracePeriodId);
  }

  /** Creates a GracePeriod with no billing event. */
  public static GracePeriod createWithoutBillingEvent(
      GracePeriodStatus type, String domainRepoId, DateTime expirationTime, String clientId) {
    return createInternal(type, domainRepoId, expirationTime, clientId, null, null, null);
  }

  /** Constructs a GracePeriod of the given type from the provided one-time BillingEvent. */
  public static GracePeriod forBillingEvent(
      GracePeriodStatus type, String domainRepoId, BillingEvent.OneTime billingEvent) {
    return create(
        type,
        domainRepoId,
        billingEvent.getBillingTime(),
        billingEvent.getClientId(),
        billingEvent.createVKey());
  }

  /**
   * Returns a clone of this {@link GracePeriod} with {@link #domainRepoId} set to the given value
   * and reconstructed history ids.
   *
   * <p>TODO(b/162739503): Remove this function after fully migrating to Cloud SQL.
   */
  public GracePeriod cloneAfterOfyLoad(String domainRepoId) {
    GracePeriod clone = clone(this);
    clone.domainRepoId = checkArgumentNotNull(domainRepoId);
    clone.restoreHistoryIds();
    return clone;
  }

  /**
   * Returns a clone of this {@link GracePeriod} with {@link #billingEventRecurring} set to the
   * given value.
   *
   * <p>TODO(b/162231099): Remove this function after duplicate id issue is solved.
   */
  public GracePeriod cloneWithRecurringBillingEvent(VKey<BillingEvent.Recurring> recurring) {
    GracePeriod clone = clone(this);
    clone.billingEventRecurring = recurring;
    return clone;
  }

  /**
   * Returns a clone of this {@link GracePeriod} with prepopulated {@link #gracePeriodId} generated
   * by {@link ObjectifyService#allocateId()}.
   *
   * <p>TODO(shicong): Figure out how to generate the id only when the entity is used for Cloud SQL.
   */
  @VisibleForTesting
  public GracePeriod cloneWithPrepopulatedId() {
    GracePeriod clone = clone(this);
    clone.gracePeriodId = ObjectifyService.allocateId();
    return clone;
  }

  /** Entity class to represent a historic {@link GracePeriod}. */
  @Entity(name = "GracePeriodHistory")
  @Table(indexes = @Index(columnList = "domainRepoId"))
  static class GracePeriodHistory extends GracePeriodBase {
    @Id Long gracePeriodHistoryRevisionId;

    /** ID for the associated {@link DomainHistory} entity. */
    Long domainHistoryRevisionId;

    @Override
    @Access(AccessType.PROPERTY)
    public long getGracePeriodId() {
      return super.getGracePeriodId();
    }

    static GracePeriodHistory createFrom(long historyRevisionId, GracePeriod gracePeriod) {
      GracePeriodHistory instance = new GracePeriodHistory();
      instance.gracePeriodHistoryRevisionId = ObjectifyService.allocateId();
      instance.domainHistoryRevisionId = historyRevisionId;
      instance.gracePeriodId = gracePeriod.gracePeriodId;
      instance.type = gracePeriod.type;
      instance.domainRepoId = gracePeriod.domainRepoId;
      instance.expirationTime = gracePeriod.expirationTime;
      instance.clientId = gracePeriod.clientId;
      instance.billingEventOneTime = gracePeriod.billingEventOneTime;
      instance.billingEventOneTimeHistoryId = gracePeriod.billingEventOneTimeHistoryId;
      instance.billingEventRecurring = gracePeriod.billingEventRecurring;
      instance.billingEventRecurringHistoryId = gracePeriod.billingEventRecurringHistoryId;
      return instance;
    }
  }
}
