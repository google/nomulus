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

package google.registry.model.transfer;

import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.Ignore;
import com.googlecode.objectify.annotation.IgnoreSave;
import com.googlecode.objectify.annotation.Unindex;
import com.googlecode.objectify.condition.IfNull;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.Period;
import google.registry.model.domain.Period.Unit;
import google.registry.model.poll.PollMessage;
import google.registry.persistence.VKey;
import javax.annotation.Nullable;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.Embedded;
import org.joda.time.DateTime;

/** Transfer data for domain. */
@Embed
@Unindex
@Embeddable
public class DomainTransferData extends TransferData<DomainTransferData.Builder> {
  public static final DomainTransferData EMPTY = new DomainTransferData();

  /**
   * The period to extend the registration upon completion of the transfer.
   *
   * <p>By default, domain transfers are for one year. This can be changed to zero by using the
   * superuser EPP extension.
   */
  @Embedded
  @AttributeOverrides({
    @AttributeOverride(name = "unit", column = @Column(name = "transfer_renew_period_unit")),
    @AttributeOverride(name = "value", column = @Column(name = "transfer_renew_period_value"))
  })
  Period transferPeriod = Period.create(1, Unit.YEARS);

  /**
   * The registration expiration time resulting from the approval - speculative or actual - of the
   * most recent transfer request, applicable for domains only.
   *
   * <p>For pending transfers, this is the expiration time that will take effect under a projected
   * server approval. For approved transfers, this is the actual expiration time of the domain as of
   * the moment of transfer completion. For rejected or cancelled transfers, this field will be
   * reset to null.
   *
   * <p>Note that even when this field is set, it does not necessarily mean that the post-transfer
   * domain has a new expiration time. Superuser transfers may not include a bundled 1 year renewal
   * at all, or even when a renewal is bundled, for a transfer during the autorenew grace period the
   * bundled renewal simply subsumes the recent autorenewal, resulting in the same expiration time.
   */
  // TODO(b/36405140): backfill this field for existing domains to which it should apply.
  @Column(name = "transfer_registration_expiration_time")
  DateTime transferredRegistrationExpirationTime;

  @Ignore
  @Column(name = "transfer_billing_cancellation_id")
  Long billingCancellationId;

  /**
   * The regular one-time billing event that will be charged for a server-approved transfer.
   *
   * <p>This field should be null if there is not currently a pending transfer or if the object
   * being transferred is not a domain.
   *
   * <p>TODO(b/158230654) Remove unused columns for TransferData in Contact table.
   */
  @IgnoreSave(IfNull.class)
  @Column(name = "transfer_billing_event_id")
  VKey<BillingEvent.OneTime> serverApproveBillingEvent;

  /**
   * The autorenew billing event that should be associated with this resource after the transfer.
   *
   * <p>This field should be null if there is not currently a pending transfer or if the object
   * being transferred is not a domain.
   */
  @IgnoreSave(IfNull.class)
  @Column(name = "transfer_billing_recurrence_id")
  VKey<BillingEvent.Recurring> serverApproveAutorenewEvent;

  /**
   * The autorenew poll message that should be associated with this resource after the transfer.
   *
   * <p>This field should be null if there is not currently a pending transfer or if the object
   * being transferred is not a domain.
   */
  @IgnoreSave(IfNull.class)
  @Column(name = "transfer_autorenew_poll_message_id")
  VKey<PollMessage.Autorenew> serverApproveAutorenewPollMessage;

  @Override
  public Builder copyConstantFieldsToBuilder() {
    return super.copyConstantFieldsToBuilder().setTransferPeriod(this.transferPeriod);
  }

  public Period getTransferPeriod() {
    return transferPeriod;
  }

  @Nullable
  public DateTime getTransferredRegistrationExpirationTime() {
    return transferredRegistrationExpirationTime;
  }

  @Nullable
  public VKey<BillingEvent.OneTime> getServerApproveBillingEvent() {
    return serverApproveBillingEvent;
  }

  @Nullable
  public VKey<BillingEvent.Recurring> getServerApproveAutorenewEvent() {
    return serverApproveAutorenewEvent;
  }

  @Nullable
  public VKey<PollMessage.Autorenew> getServerApproveAutorenewPollMessage() {
    return serverApproveAutorenewPollMessage;
  }

  @Override
  public boolean isEmpty() {
    return EMPTY.equals(this);
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  public static class Builder extends TransferData.Builder<DomainTransferData, Builder> {
    /** Create a {@link DomainTransferData.Builder} wrapping a new instance. */
    public Builder() {}

    /** Create a {@link Builder} wrapping the given instance. */
    private Builder(DomainTransferData instance) {
      super(instance);
    }

    public Builder setTransferPeriod(Period transferPeriod) {
      getInstance().transferPeriod = transferPeriod;
      return this;
    }

    public Builder setTransferredRegistrationExpirationTime(
        DateTime transferredRegistrationExpirationTime) {
      getInstance().transferredRegistrationExpirationTime = transferredRegistrationExpirationTime;
      return this;
    }

    public Builder setServerApproveBillingEvent(
        VKey<BillingEvent.OneTime> serverApproveBillingEvent) {
      getInstance().serverApproveBillingEvent = serverApproveBillingEvent;
      return this;
    }

    public Builder setServerApproveAutorenewEvent(
        VKey<BillingEvent.Recurring> serverApproveAutorenewEvent) {
      getInstance().serverApproveAutorenewEvent = serverApproveAutorenewEvent;
      return this;
    }

    public Builder setServerApproveAutorenewPollMessage(
        VKey<PollMessage.Autorenew> serverApproveAutorenewPollMessage) {
      getInstance().serverApproveAutorenewPollMessage = serverApproveAutorenewPollMessage;
      return this;
    }
  }
}
