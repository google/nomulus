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

import com.googlecode.objectify.annotation.Embed;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.persistence.VKey;
import javax.annotation.Nullable;
import javax.persistence.Entity;
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
public class GracePeriod extends GracePeriodBase {

  private static GracePeriod createInternal(
      GracePeriodStatus type,
      String domainRepoId,
      DateTime expirationTime,
      String clientId,
      @Nullable VKey<BillingEvent.OneTime> billingEventOneTime,
      @Nullable VKey<BillingEvent.Recurring> billingEventRecurring) {
    checkArgument((billingEventOneTime == null) || (billingEventRecurring == null),
        "A grace period can have at most one billing event");
    checkArgument(
        (billingEventRecurring != null) == GracePeriodStatus.AUTO_RENEW.equals(type),
        "Recurring billing events must be present on (and only on) autorenew grace periods");
    GracePeriod instance = new GracePeriod();
    instance.type = checkArgumentNotNull(type);
    instance.domainRepoId = checkArgumentNotNull(domainRepoId);
    instance.expirationTime = checkArgumentNotNull(expirationTime);
    instance.clientId = checkArgumentNotNull(clientId);
    instance.billingEventOneTime = billingEventOneTime;
    instance.billingEventRecurring = billingEventRecurring;
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
    return createInternal(type, domainRepoId, expirationTime, clientId, billingEventOneTime, null);
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
        type, domainRepoId, expirationTime, clientId, null, billingEventRecurring);
  }

  /** Creates a GracePeriod with no billing event. */
  public static GracePeriod createWithoutBillingEvent(
      GracePeriodStatus type, String domainRepoId, DateTime expirationTime, String clientId) {
    return createInternal(type, domainRepoId, expirationTime, clientId, null, null);
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
   * Returns a clone of this {@link GracePeriod} with {@link #domainRepoId} set to the given value.
   *
   * <p>TODO(b/162739503): Remove this function after fully migrating to Cloud SQL.
   */
  public GracePeriod cloneWithDomainRepoId(String domainRepoId) {
    GracePeriod clone = clone(this);
    clone.domainRepoId = checkArgumentNotNull(domainRepoId);
    return clone;
  }

}
