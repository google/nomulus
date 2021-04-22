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

package google.registry.model.domain;

import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.Ignore;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.persistence.BillingVKey.BillingEventVKey;
import google.registry.persistence.BillingVKey.BillingRecurrenceVKey;
import google.registry.persistence.VKey;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Column;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.MappedSuperclass;
import javax.persistence.Transient;
import org.joda.time.DateTime;

/** Base class containing common fields and methods for {@link GracePeriod}. */
@Embed
@MappedSuperclass
@Access(AccessType.FIELD)
public class GracePeriodBase extends ImmutableObject {

  /** Unique id required for hibernate representation. */
  @Transient long gracePeriodId;

  /** Repository id for the domain which this grace period belongs to. */
  @Ignore
  @Column(nullable = false)
  String domainRepoId;

  /** The type of grace period. */
  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  GracePeriodStatus type;

  /** When the grace period ends. */
  @Column(nullable = false)
  DateTime expirationTime;

  /** The registrar to bill. */
  @Column(name = "registrarId", nullable = false)
  String clientId;

  /**
   * The one-time billing event corresponding to the action that triggered this grace period, or
   * null if not applicable. Not set for autorenew grace periods (which instead use the field {@code
   * billingEventRecurring}) or for redemption grace periods (since deletes have no cost).
   */
  // NB: Would @IgnoreSave(IfNull.class), but not allowed for @Embed collections.
  @Access(AccessType.FIELD)
  BillingEventVKey billingEventOneTime = null;

  /**
   * The recurring billing event corresponding to the action that triggered this grace period, if
   * applicable - i.e. if the action was an autorenew - or null in all other cases.
   */
  // NB: Would @IgnoreSave(IfNull.class), but not allowed for @Embed collections.
  @Access(AccessType.FIELD)
  BillingRecurrenceVKey billingEventRecurring = null;

  public long getGracePeriodId() {
    return gracePeriodId;
  }

  public GracePeriodStatus getType() {
    return type;
  }

  public String getDomainRepoId() {
    return domainRepoId;
  }

  public DateTime getExpirationTime() {
    return expirationTime;
  }

  public String getClientId() {
    return clientId;
  }

  /** This method is private because it is only used by Hibernate. */
  @SuppressWarnings("unused")
  private void setGracePeriodId(long gracePeriodId) {
    this.gracePeriodId = gracePeriodId;
  }

  /** Returns true if this GracePeriod has an associated BillingEvent; i.e. if it's refundable. */
  public boolean hasBillingEvent() {
    return billingEventOneTime != null || billingEventRecurring != null;
  }

  /**
   * Returns the one time billing event. The value will only be non-null if the type of this grace
   * period is not AUTO_RENEW.
   */
  public VKey<BillingEvent.OneTime> getOneTimeBillingEvent() {
    return billingEventOneTime == null ? null : billingEventOneTime.createVKey();
  }

  /**
   * Returns the recurring billing event. The value will only be non-null if the type of this grace
   * period is AUTO_RENEW.
   */
  public VKey<BillingEvent.Recurring> getRecurringBillingEvent() {
    return billingEventRecurring == null ? null : billingEventRecurring.createVKey();
  }
}
