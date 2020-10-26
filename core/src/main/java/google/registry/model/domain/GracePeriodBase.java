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

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.Ignore;
import google.registry.model.ImmutableObject;
import google.registry.model.ModelUtils;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.persistence.VKey;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.persistence.Column;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.MappedSuperclass;
import org.joda.time.DateTime;

/** Base class containing common fields and methods for {@link GracePeriod}. */
@Embed
@MappedSuperclass
public class GracePeriodBase extends ImmutableObject {

  /** Unique id required for hibernate representation. */
  @javax.persistence.Id
  @Ignore
  Long id;

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
  @Column(name = "billing_event_id")
  VKey<OneTime> billingEventOneTime = null;

  @Ignore
  @Column(name = "billing_event_history_id")
  Long billingEventOneTimeHistoryId;

  /**
   * The recurring billing event corresponding to the action that triggered this grace period, if
   * applicable - i.e. if the action was an autorenew - or null in all other cases.
   */
  // NB: Would @IgnoreSave(IfNull.class), but not allowed for @Embed collections.
  @Column(name = "billing_recurrence_id")
  VKey<BillingEvent.Recurring> billingEventRecurring = null;

  @Ignore
  @Column(name = "billing_recurrence_history_id")
  Long billingEventRecurringHistoryId;

  public long getId() {
    return id;
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

  /** Returns true if this GracePeriod has an associated BillingEvent; i.e. if it's refundable. */
  public boolean hasBillingEvent() {
    return billingEventOneTime != null || billingEventRecurring != null;
  }

  /**
   * Returns the one time billing event. The value will only be non-null if the type of this grace
   * period is not AUTO_RENEW.
   */
  public VKey<BillingEvent.OneTime> getOneTimeBillingEvent() {
    restoreOfyKeys();
    return billingEventOneTime;
  }

  /**
   * Returns the recurring billing event. The value will only be non-null if the type of this grace
   * period is AUTO_RENEW.
   */
  public VKey<BillingEvent.Recurring> getRecurringBillingEvent() {
    restoreOfyKeys();
    return billingEventRecurring;
  }

  /**
   * Restores history ids for composite VKeys after a load from datastore.
   *
   * <p>For use by DomainContent.load() ONLY.
   */
  protected void restoreHistoryIds() {
    billingEventOneTimeHistoryId = DomainBase.getHistoryId(billingEventOneTime);
    billingEventRecurringHistoryId = DomainBase.getHistoryId(billingEventRecurring);
  }

  /**
   * Override {@link ImmutableObject#getSignificantFields()} to exclude "id", which breaks equality
   * testing in the unit tests.
   */
  @Override
  protected Map<Field, Object> getSignificantFields() {
    restoreOfyKeys();
    // Can't use streams or ImmutableMap because we can have null values.
    Map<Field, Object> result = new LinkedHashMap();
    for (Map.Entry<Field, Object> entry : ModelUtils.getFieldValues(this).entrySet()) {
      if (!entry.getKey().getName().equals("id")) {
        result.put(entry.getKey(), entry.getValue());
      }
    }
    return result;
  }

  /**
   * Restores Ofy keys in the billing events.
   *
   * <p>This must be called by all methods that access the one time or recurring billing event keys.
   * When the billing event keys are loaded from SQL, they are loaded as asymmetric keys because the
   * database columns that we load them from do not contain all of the information necessary to
   * reconsitute the Ofy side of the key. In other cases, we restore the Ofy key during the
   * hibernate {@link javax.persistence.PostLoad} method from the other fields of the object, but we
   * have been unable to make this work with hibernate's internal persistence model in this case
   * because the {@link GracePeriod}'s hash code is evaluated prior to these calls, and would be
   * invalidated by changing the fields.
   */
  private final synchronized void restoreOfyKeys() {
    if (billingEventOneTime != null && !billingEventOneTime.maybeGetOfyKey().isPresent()) {
      billingEventOneTime =
          DomainBase.restoreOfyFrom(
              Key.create(DomainBase.class, domainRepoId),
              billingEventOneTime,
              billingEventOneTimeHistoryId);
    }
    if (billingEventRecurring != null && !billingEventRecurring.maybeGetOfyKey().isPresent()) {
      billingEventRecurring =
          DomainBase.restoreOfyFrom(
              Key.create(DomainBase.class, domainRepoId),
              billingEventRecurring,
              billingEventRecurringHistoryId);
    }
  }
}
