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

import google.registry.model.ImmutableObject;
import google.registry.model.UnsafeSerializable;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.persistence.VKey;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Transient;
import org.joda.time.DateTime;

/** Base class containing common fields and methods for {@link GracePeriod}. */
@MappedSuperclass
@Access(AccessType.FIELD)
public class GracePeriodBase extends ImmutableObject implements UnsafeSerializable {

  /** Unique id required for hibernate representation. */
  @Transient long gracePeriodId;

  /** Repository id for the domain which this grace period belongs to. */
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
   * billingEventRecurrence}) or for redemption grace periods (since deletes have no cost).
   */
  // NB: Would @IgnoreSave(IfNull.class), but not allowed for @Embed collections.
  @Access(AccessType.FIELD)
  @Column(name = "billing_event_id")
  VKey<BillingEvent> billingEvent = null;

  /**
   * The Recurrence corresponding to the action that triggered this grace period, if applicable -
   * i.e. if the action was an autorenew - or null in all other cases.
   */
  // NB: Would @IgnoreSave(IfNull.class), but not allowed for @Embed collections.
  @Access(AccessType.FIELD)
  @Column(name = "billing_recurrence_id")
  VKey<BillingRecurrence> billingRecurrence = null;

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

  public String getRegistrarId() {
    return clientId;
  }

  /** This method is private because it is only used by Hibernate. */
  @SuppressWarnings("unused")
  private void setGracePeriodId(long gracePeriodId) {
    this.gracePeriodId = gracePeriodId;
  }

  /** Returns true if this GracePeriod has an associated BillingEvent; i.e. if it's refundable. */
  public boolean hasBillingEvent() {
    return billingEvent != null || billingRecurrence != null;
  }

  /**
   * Returns the one time billing event. The value will only be non-null if the type of this grace
   * period is not AUTO_RENEW.
   */
  public VKey<BillingEvent> getBillingEvent() {
    return billingEvent;
  }

  /**
   * Returns the Recurrence. The value will only be non-null if the type of this grace period is
   * AUTO_RENEW.
   */
  public VKey<BillingRecurrence> getBillingRecurrence() {
    return billingRecurrence;
  }
}
