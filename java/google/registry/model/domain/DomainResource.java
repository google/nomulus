// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.collect.Sets.intersection;
import static google.registry.model.EppResourceUtils.projectResourceOntoBuilderAtTime;
import static google.registry.model.EppResourceUtils.setAutomaticTransferSuccessProperties;
import static google.registry.model.ofy.Ofy.RECOMMENDED_MEMCACHE_EXPIRATION;
import static google.registry.util.CollectionUtils.difference;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static google.registry.util.CollectionUtils.union;
import static google.registry.util.DateTimeUtils.earliestOf;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;
import static google.registry.util.DateTimeUtils.leapSafeAddYears;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Cache;
import com.googlecode.objectify.annotation.EntitySubclass;
import com.googlecode.objectify.annotation.IgnoreSave;
import com.googlecode.objectify.condition.IfNull;
import google.registry.model.EppResource.ForeignKeyedEppResource;
import google.registry.model.EppResource.ResourceWithTransferData;
import google.registry.model.annotations.ExternalMessagingName;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.poll.PollMessage;
import google.registry.model.registry.Registry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import java.util.HashSet;
import java.util.Set;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.XmlType;
import org.joda.time.DateTime;
import org.joda.time.Interval;

/**
 * A persistable domain resource including mutable and non-mutable fields.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5731">RFC 5731</a>
 */
@XmlRootElement(name = "infData")
@XmlType(propOrder = {
    "fullyQualifiedDomainName",
    "repoId",
    "status",
    "marshalledRegistrant",
    "marshalledContacts",
    "marshalledNameservers",
    "subordinateHosts",
    "currentSponsorClientId",
    "creationClientId",
    "creationTime",
    "lastEppUpdateClientId",
    "lastEppUpdateTime",
    "registrationExpirationTime",
    "lastTransferTime",
    "authInfo"})
@Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
@EntitySubclass(index = true)
@ExternalMessagingName("domain")
public class DomainResource extends DomainBase
    implements ForeignKeyedEppResource, ResourceWithTransferData {

  /** The max number of years that a domain can be registered for, as set by ICANN policy. */
  public static final int MAX_REGISTRATION_YEARS = 10;

  /** Status values which prohibit DNS information from being published. */
  private static final ImmutableSet<StatusValue> DNS_PUBLISHING_PROHIBITED_STATUSES =
      ImmutableSet.of(
          StatusValue.CLIENT_HOLD,
          StatusValue.INACTIVE,
          StatusValue.PENDING_DELETE,
          StatusValue.SERVER_HOLD);

  /** Fully qualified host names of this domain's active subordinate hosts. */
  @XmlElement(name = "host")
  Set<String> subordinateHosts;

  /** When this domain's registration will expire. */
  @XmlElement(name = "exDate")
  DateTime registrationExpirationTime;

  /**
   * The poll message associated with this domain being deleted.
   *
   * <p>This field should be null if the domain is not in pending delete. If it is, the field should
   * refer to a {@link PollMessage} timed to when the domain is fully deleted. If the domain is
   * restored, the message should be deleted.
   */
  @XmlTransient
  Key<PollMessage.OneTime> deletePollMessage;

  /**
   * The recurring billing event associated with this domain's autorenewals.
   *
   * <p>The recurrence should be open ended unless the domain is in pending delete or fully deleted,
   * in which case it should be closed at the time the delete was requested. Whenever the domain's
   * {@link #registrationExpirationTime} is changed the recurrence should be closed, a new one
   * should be created, and this field should be updated to point to the new one.
   */
  @XmlTransient
  Key<BillingEvent.Recurring> autorenewBillingEvent;

  /**
   * The recurring poll message associated with this domain's autorenewals.
   *
   * <p>The recurrence should be open ended unless the domain is in pending delete or fully deleted,
   * in which case it should be closed at the time the delete was requested. Whenever the domain's
   * {@link #registrationExpirationTime} is changed the recurrence should be closed, a new one
   * should be created, and this field should be updated to point to the new one.
   */
  @XmlTransient
  Key<PollMessage.Autorenew> autorenewPollMessage;

  /** The unexpired grace periods for this domain (some of which may not be active yet). */
  @XmlTransient
  Set<GracePeriod> gracePeriods;

  /**
   * The id of the signed mark that was used to create the sunrise application for this domain.
   * Will only be populated for domains allocated from a sunrise application.
   */
  @IgnoreSave(IfNull.class)
  @XmlTransient
  String smdId;

  /**
   * The time that the application used to allocate this domain was created. Will only be populated
   * for domains allocated from an application.
   */
  @IgnoreSave(IfNull.class)
  @XmlTransient
  DateTime applicationTime;

  /**
   * A key to the application used to allocate this domain. Will only be populated for domains
   * allocated from an application.
   */
  @IgnoreSave(IfNull.class)
  @XmlTransient
  Key<DomainApplication> application;

  /** Data about any pending or past transfers on this domain. */
  @XmlTransient
  TransferData transferData;

  /**
   * The time that this resource was last transferred.
   *
   * <p>Can be null if the resource has never been transferred.
   */
  @XmlElement(name = "trDate")
  DateTime lastTransferTime;

  public ImmutableSet<String> getSubordinateHosts() {
    return nullToEmptyImmutableCopy(subordinateHosts);
  }

  public DateTime getRegistrationExpirationTime() {
    return registrationExpirationTime;
  }

  public Key<PollMessage.OneTime> getDeletePollMessage() {
    return deletePollMessage;
  }

  public Key<BillingEvent.Recurring> getAutorenewBillingEvent() {
    return autorenewBillingEvent;
  }

  public Key<PollMessage.Autorenew> getAutorenewPollMessage() {
    return autorenewPollMessage;
  }

  public ImmutableSet<GracePeriod> getGracePeriods() {
    return nullToEmptyImmutableCopy(gracePeriods);
  }

  public String getSmdId() {
    return smdId;
  }

  public DateTime getApplicationTime() {
    return applicationTime;
  }

  public Key<DomainApplication> getApplication() {
    return application;
  }

  @Override
  public final TransferData getTransferData() {
    return Optional.fromNullable(transferData).or(TransferData.EMPTY);
  }

  @Override
  public DateTime getLastTransferTime() {
    return lastTransferTime;
  }

  @Override
  public String getForeignKey() {
    return fullyQualifiedDomainName;
  }

  /** Returns true if DNS information should be published for the given domain. */
  public boolean shouldPublishToDns() {
    return intersection(getStatusValues(), DNS_PUBLISHING_PROHIBITED_STATUSES).isEmpty();
  }

  /**
   * Returns the Registry Grace Period Statuses for this domain.
   *
   * <p>This collects all statuses from the domain's {@link GracePeriod} entries and also adds the
   * PENDING_DELETE status if needed.
   */
  public ImmutableSet<GracePeriodStatus> getGracePeriodStatuses() {
    Set<GracePeriodStatus> gracePeriodStatuses = new HashSet<>();
    for (GracePeriod gracePeriod : getGracePeriods()) {
      gracePeriodStatuses.add(gracePeriod.getType());
    }
    if (getStatusValues().contains(StatusValue.PENDING_DELETE)
        && !gracePeriodStatuses.contains(GracePeriodStatus.REDEMPTION)) {
      gracePeriodStatuses.add(GracePeriodStatus.PENDING_DELETE);
    }
    return ImmutableSet.copyOf(gracePeriodStatuses);
  }

  /**
   * Returns the Registry Grace Period expiration date for the specified type of grace period for
   * this domain, or null if there is no grace period of the specified type.
   */
  public Optional<DateTime> getGracePeriodExpirationTime(GracePeriodStatus gracePeriodType) {
    for (GracePeriod gracePeriod : getGracePeriods()) {
      if (gracePeriod.getType() == gracePeriodType) {
        return Optional.of(gracePeriod.getExpirationTime());
      }
    }
    return Optional.absent();
  }

  /**
   * Checks to see if the domain is in a particular type of grace period at the specified time. We
   * only check the expiration time, because grace periods are always assumed to start at the
   * beginning of time. This could be confusing if asOfDate is in the past. For instance, the Add
   * Grace Period will appear to last from the beginning of time until 5 days after the domain is
   * created.
   */
  public boolean doesAnyGracePeriodOfTypeExpireAfter(
      GracePeriodStatus gracePeriodType, DateTime asOfDate) {
    for (GracePeriod gracePeriod : getGracePeriods()) {
      if ((gracePeriod.getType() == gracePeriodType)
          && gracePeriod.getExpirationTime().isAfter(asOfDate)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The logic in this method, which handles implicit server approval of transfers, very closely
   * parallels the logic in {@code DomainTransferApproveFlow} which handles explicit client
   * approvals.
   */
  @Override
  public DomainResource cloneProjectedAtTime(final DateTime now) {

    TransferData transferData = getTransferData();
    DateTime transferExpirationTime = transferData.getPendingTransferExpirationTime();

    // If there's a pending transfer that has expired, handle it.
    if (TransferStatus.PENDING.equals(transferData.getTransferStatus())
        && isBeforeOrAt(transferExpirationTime, now)) {
      // Project until just before the transfer time. This will handle the case of an autorenew
      // before the transfer was even requested or during the request period.
      // If the transfer time is precisely the moment that the domain expires, there will not be an
      // autorenew billing event (since we end the recurrence at transfer time and recurrences are
      // exclusive of their ending), and we can just proceed with the transfer.
      DomainResource domainAtTransferTime =
          cloneProjectedAtTime(transferExpirationTime.minusMillis(1));
      // If we are within an autorenew grace period, the transfer will subsume the autorenew. There
      // will already be a cancellation written in advance by the transfer request flow, so we don't
      // need to worry about billing, but we do need to reduce the number of years added to the
      // expiration time by one to account for the year added by the autorenew.
      int extraYears = transferData.getExtendedRegistrationYears();
      if (domainAtTransferTime.getGracePeriodStatuses().contains(GracePeriodStatus.AUTO_RENEW)) {
        extraYears--;
      }
      // Set the expiration, autorenew events, and grace period for the transfer. (Transfer ends
      // all other graces).
      Builder builder = domainAtTransferTime.asBuilder()
          // Extend the registration by the correct number of years from the expiration time that
          // was current on the domain right before the transfer, capped at 10 years from the
          // moment of the transfer.
          .setRegistrationExpirationTime(extendRegistrationWithCap(
              transferExpirationTime,
              domainAtTransferTime.getRegistrationExpirationTime(),
              extraYears))
          // Set the speculatively-written new autorenew events as the domain's autorenew events.
          .setAutorenewBillingEvent(transferData.getServerApproveAutorenewEvent())
          .setAutorenewPollMessage(transferData.getServerApproveAutorenewPollMessage())
          // Set the grace period using a key to the prescheduled transfer billing event.  Not using
          // GracePeriod.forBillingEvent() here in order to avoid the actual datastore fetch.
          .setGracePeriods(ImmutableSet.of(GracePeriod.create(
              GracePeriodStatus.TRANSFER,
              transferExpirationTime.plus(Registry.get(getTld()).getTransferGracePeriodLength()),
              transferData.getGainingClientId(),
              transferData.getServerApproveBillingEvent())));
      // Set all remaining transfer properties.
      setAutomaticTransferSuccessProperties(builder, transferData);
      // Finish projecting to now.
      return builder.build().cloneProjectedAtTime(now);
    }

    // There is no transfer. Do any necessary autorenews.

    Builder builder = asBuilder();
    if (isBeforeOrAt(registrationExpirationTime, now)) {
      // Autorenew by the number of years between the old expiration time and now.
      DateTime lastAutorenewTime = leapSafeAddYears(
          registrationExpirationTime,
          new Interval(registrationExpirationTime, now).toPeriod().getYears());
      DateTime newExpirationTime  = lastAutorenewTime.plusYears(1);
      builder
          .setRegistrationExpirationTime(newExpirationTime)
          .addGracePeriod(GracePeriod.createForRecurring(
              GracePeriodStatus.AUTO_RENEW,
              lastAutorenewTime.plus(Registry.get(getTld()).getAutoRenewGracePeriodLength()),
              getCurrentSponsorClientId(),
              autorenewBillingEvent));
    }

    // Remove any grace periods that have expired.
    DomainResource almostBuilt = builder.build();
    builder = almostBuilt.asBuilder();
    for (GracePeriod gracePeriod : almostBuilt.getGracePeriods()) {
      if (isBeforeOrAt(gracePeriod.getExpirationTime(), now)) {
        builder.removeGracePeriod(gracePeriod);
      }
    }

    // Handle common properties like setting or unsetting linked status. This also handles the
    // general case of pending transfers for other resource types, but since we've always handled
    // a pending transfer by this point that's a no-op for domains.
    projectResourceOntoBuilderAtTime(almostBuilt, builder, now);
    return builder.build();
  }

  /** Return what the expiration time would be if the given number of years were added to it. */
  public static DateTime extendRegistrationWithCap(
      DateTime now, DateTime currentExpirationTime, Integer extendedRegistrationYears) {
    // We must cap registration at the max years (aka 10), even if that truncates the last year.
    return earliestOf(
        leapSafeAddYears(
            currentExpirationTime, Optional.fromNullable(extendedRegistrationYears).or(0)),
        leapSafeAddYears(now, MAX_REGISTRATION_YEARS));
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link DomainResource}, since it is immutable. */
  public static class Builder extends DomainBase.Builder<DomainResource, Builder>
      implements BuilderWithTransferData<Builder> {

    public Builder() {}

    private Builder(DomainResource instance) {
      super(instance);
    }

    @Override
    public DomainResource build() {
      // If TransferData is totally empty, set it to null.
      if (TransferData.EMPTY.equals(getInstance().transferData)) {
        setTransferData(null);
      }
      // A DomainResource has status INACTIVE if there are no nameservers.
      if (getInstance().getNameservers().isEmpty()) {
        addStatusValue(StatusValue.INACTIVE);
      } else {  // There are nameservers, so make sure INACTIVE isn't there.
        removeStatusValue(StatusValue.INACTIVE);
      }
      // This must be called after we add or remove INACTIVE, since that affects whether we get OK.
      return super.build();
    }

    public Builder setSubordinateHosts(ImmutableSet<String> subordinateHosts) {
      getInstance().subordinateHosts = subordinateHosts;
      return thisCastToDerived();
    }

    public Builder addSubordinateHost(String hostToAdd) {
      return setSubordinateHosts(ImmutableSet.copyOf(
          union(getInstance().getSubordinateHosts(), hostToAdd)));
    }

    public Builder removeSubordinateHost(String hostToRemove) {
      return setSubordinateHosts(ImmutableSet.copyOf(
          difference(getInstance().getSubordinateHosts(), hostToRemove)));
    }

    public Builder setRegistrationExpirationTime(DateTime registrationExpirationTime) {
      getInstance().registrationExpirationTime = registrationExpirationTime;
      return this;
    }

    public Builder setDeletePollMessage(Key<PollMessage.OneTime> deletePollMessage) {
      getInstance().deletePollMessage = deletePollMessage;
      return this;
    }

    public Builder setAutorenewBillingEvent(Key<BillingEvent.Recurring> autorenewBillingEvent) {
      getInstance().autorenewBillingEvent = autorenewBillingEvent;
      return this;
    }

    public Builder setAutorenewPollMessage(Key<PollMessage.Autorenew> autorenewPollMessage) {
      getInstance().autorenewPollMessage = autorenewPollMessage;
      return this;
    }

    public Builder setSmdId(String smdId) {
      getInstance().smdId = smdId;
      return this;
    }

    public Builder setApplicationTime(DateTime applicationTime) {
      getInstance().applicationTime = applicationTime;
      return this;
    }

    public Builder setApplication(Key<DomainApplication> application) {
      getInstance().application = application;
      return this;
    }

    public Builder setGracePeriods(ImmutableSet<GracePeriod> gracePeriods) {
      getInstance().gracePeriods = gracePeriods;
      return this;
    }

    public Builder addGracePeriod(GracePeriod gracePeriod) {
      getInstance().gracePeriods = union(getInstance().getGracePeriods(), gracePeriod);
      return this;
    }

    public Builder removeGracePeriod(GracePeriod gracePeriod) {
      getInstance().gracePeriods = difference(getInstance().getGracePeriods(), gracePeriod);
      return this;
    }

    @Override
    public Builder setTransferData(TransferData transferData) {
      getInstance().transferData = transferData;
      return thisCastToDerived();
    }

    @Override
    public Builder setLastTransferTime(DateTime lastTransferTime) {
      getInstance().lastTransferTime = lastTransferTime;
      return thisCastToDerived();
    }
  }
}

