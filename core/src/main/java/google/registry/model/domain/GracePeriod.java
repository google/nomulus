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

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.Ignore;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.rgp.GracePeriodStatus;
import javax.annotation.Nullable;
import javax.persistence.Column;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import org.joda.time.DateTime;

/**
 * A domain grace period with an expiration time.
 *
 * <p>When a grace period expires, it is lazily removed from the {@link DomainBase} the next time
 * the resource is loaded from Datastore.
 */
@Embed
@javax.persistence.Entity
public class GracePeriod extends ImmutableObject {

  @javax.persistence.Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Ignore
  /** Unique id required for hibernate representation. */
  long id;

  /** The type of grace period. */
  GracePeriodStatus type;

  /** When the grace period ends. */
  DateTime expirationTime;

  /** The registrar to bill. */
  @Column(name = "registrarId")
  String clientId;

  /**
   * The one-time billing event corresponding to the action that triggered this grace period, or
   * null if not applicable.  Not set for autorenew grace periods (which instead use the field
   * {@code billingEventRecurring}) or for redemption grace periods (since deletes have no cost).
   */
  // NB: Would @IgnoreSave(IfNull.class), but not allowed for @Embed collections.
  Key<BillingEvent.OneTime> billingEventOneTime = null;

  /**
   * The recurring billing event corresponding to the action that triggered this grace period, if
   * applicable - i.e. if the action was an autorenew - or null in all other cases.
   */
  // NB: Would @IgnoreSave(IfNull.class), but not allowed for @Embed collections.
  Key<BillingEvent.Recurring> billingEventRecurring = null;

  public GracePeriodStatus getType() {
    return type;
  }

  public DateTime getExpirationTime() {
    return expirationTime;
  }

  public String getClientId() {
    return clientId;
  }

  /** Returns true if this GracePeriod has an associated BillingEvent; i.e. if it's refundable. */
  public boolean hasBillingEvent() {
    return billingEventOneTime != null || billingEventRecurring != null;
  }

  /**
   * Returns the one time billing event. The value will only be non-null if the type of this grace
   * period is not AUTO_RENEW.
   */

  public Key<BillingEvent.OneTime> getOneTimeBillingEvent() {
    return billingEventOneTime;
  }

  /**
   * Returns the recurring billing event. The value will only be non-null if the type of this grace
   * period is AUTO_RENEW.
   */
  public Key<BillingEvent.Recurring> getRecurringBillingEvent() {
    return billingEventRecurring;
  }

  private static GracePeriod createInternal(
       GracePeriodStatus type,
       DateTime expirationTime,
       String clientId,
       @Nullable Key<BillingEvent.OneTime> billingEventOneTime,
       @Nullable Key<BillingEvent.Recurring> billingEventRecurring) {
    checkArgument((billingEventOneTime == null) || (billingEventRecurring == null),
        "A grace period can have at most one billing event");
    checkArgument(
        (billingEventRecurring != null) == GracePeriodStatus.AUTO_RENEW.equals(type),
        "Recurring billing events must be present on (and only on) autorenew grace periods");
    GracePeriod instance = new GracePeriod();
    instance.type = checkArgumentNotNull(type);
    instance.expirationTime = checkArgumentNotNull(expirationTime);
    instance.clientId = checkArgumentNotNull(clientId);
    instance.billingEventOneTime = billingEventOneTime;
    instance.billingEventRecurring = billingEventRecurring;
    return instance;
  }

  /**
   * Creates a GracePeriod for an (optional) OneTime billing event.
   *
   * <p>Normal callers should always use {@link #forBillingEvent} instead, assuming they do not
   * need to avoid loading the BillingEvent from Datastore.  This method should typically be
   * called only from test code to explicitly construct GracePeriods.
   */
  public static GracePeriod create(
      GracePeriodStatus type,
      DateTime expirationTime,
      String clientId,
      @Nullable Key<BillingEvent.OneTime> billingEventOneTime) {
    return createInternal(type, expirationTime, clientId, billingEventOneTime, null);
  }

  /** Creates a GracePeriod for a Recurring billing event. */
  public static GracePeriod createForRecurring(
      GracePeriodStatus type,
      DateTime expirationTime,
      String clientId,
      Key<BillingEvent.Recurring> billingEventRecurring) {
    checkArgumentNotNull(billingEventRecurring, "billingEventRecurring cannot be null");
    return createInternal(type, expirationTime, clientId, null, billingEventRecurring);
  }

  /** Creates a GracePeriod with no billing event. */
  public static GracePeriod createWithoutBillingEvent(
      GracePeriodStatus type, DateTime expirationTime, String clientId) {
    return createInternal(type, expirationTime, clientId, null, null);
  }

  /** Constructs a GracePeriod of the given type from the provided one-time BillingEvent. */
  public static GracePeriod forBillingEvent(
      GracePeriodStatus type, BillingEvent.OneTime billingEvent) {
    return create(
        type, billingEvent.getBillingTime(), billingEvent.getClientId(), Key.create(billingEvent));
  }
}
