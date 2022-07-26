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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.ImmutableSortedSet.toImmutableSortedSet;
import static com.google.common.collect.Sets.difference;
import static com.google.common.collect.Sets.intersection;
import static google.registry.model.EppResourceUtils.projectResourceOntoBuilderAtTime;
import static google.registry.model.EppResourceUtils.setAutomaticTransferSuccessProperties;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.CollectionUtils.forceEmptyToNull;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static google.registry.util.CollectionUtils.union;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.earliestOf;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;
import static google.registry.util.DateTimeUtils.leapSafeAddYears;
import static google.registry.util.DomainNameUtils.canonicalizeHostname;
import static google.registry.util.DomainNameUtils.getTldFromDomainName;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Ignore;
import com.googlecode.objectify.annotation.IgnoreSave;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.condition.IfNull;
import google.registry.dns.RefreshDnsAction;
import google.registry.flows.ResourceFlowUtils;
import google.registry.model.EppResource;
import google.registry.model.EppResource.ResourceWithTransferData;
import google.registry.model.billing.BillingEvent;
import google.registry.model.common.EntityGroupRoot;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.Host;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Registry;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.persistence.VKey;
import google.registry.util.CollectionUtils;
import google.registry.util.DateTimeUtils;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.Embedded;
import javax.persistence.MappedSuperclass;
import javax.persistence.Transient;
import org.hibernate.collection.internal.PersistentSet;
import org.joda.time.DateTime;
import org.joda.time.Interval;

/**
 * A persistable domain resource including mutable and non-mutable fields.
 *
 * <p>This class deliberately does not include an {@link javax.persistence.Id} so that any
 * foreign-keyed fields can refer to the proper parent entity's ID, whether we're storing this in
 * the DB itself or as part of another entity.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5731">RFC 5731</a>
 */
@MappedSuperclass
@Embeddable
@Access(AccessType.FIELD)
public class DomainBase extends EppResource
    implements ResourceWithTransferData<DomainTransferData> {

  /** The max number of years that a domain can be registered for, as set by ICANN policy. */
  public static final int MAX_REGISTRATION_YEARS = 10;

  /** Status values which prohibit DNS information from being published. */
  private static final ImmutableSet<StatusValue> DNS_PUBLISHING_PROHIBITED_STATUSES =
      ImmutableSet.of(
          StatusValue.CLIENT_HOLD,
          StatusValue.INACTIVE,
          StatusValue.PENDING_DELETE,
          StatusValue.SERVER_HOLD);

  /**
   * Fully qualified domain name (puny-coded), which serves as the foreign key for this domain.
   *
   * <p>This is only unique in the sense that for any given lifetime specified as the time range
   * from (creationTime, deletionTime) there can only be one domain in Datastore with this name.
   * However, there can be many domains with the same name and non-overlapping lifetimes.
   *
   * @invariant fullyQualifiedDomainName == fullyQualifiedDomainName.toLowerCase(Locale.ENGLISH)
   */
  // TODO(b/177567432): Rename this to domainName when we are off Datastore
  @Column(name = "domainName")
  @Index
  String fullyQualifiedDomainName;

  /** The top level domain this is under, dernormalized from {@link #fullyQualifiedDomainName}. */
  @Index String tld;

  /** References to hosts that are the nameservers for the domain. */
  @EmptySetToNull @Index @Transient Set<VKey<Host>> nsHosts;

  /** Contacts. */
  VKey<ContactResource> adminContact;

  VKey<ContactResource> billingContact;
  VKey<ContactResource> techContact;
  VKey<ContactResource> registrantContact;

  /** Authorization info (aka transfer secret) of the domain. */
  @Embedded
  @AttributeOverrides({
    @AttributeOverride(name = "pw.value", column = @Column(name = "auth_info_value")),
    @AttributeOverride(name = "pw.repoId", column = @Column(name = "auth_info_repo_id")),
  })
  DomainAuthInfo authInfo;

  /** Data used to construct DS records for this domain. */
  @Transient Set<DelegationSignerData> dsData;

  /**
   * The claims notice supplied when this domain was created, if there was one.
   *
   * <p>It's {@literal @}XmlTransient because it's not returned in an info response.
   */
  @Embedded
  @AttributeOverrides({
    @AttributeOverride(name = "noticeId.tcnId", column = @Column(name = "launch_notice_tcn_id")),
    @AttributeOverride(
        name = "noticeId.validatorId",
        column = @Column(name = "launch_notice_validator_id")),
    @AttributeOverride(
        name = "expirationTime",
        column = @Column(name = "launch_notice_expiration_time")),
    @AttributeOverride(
        name = "acceptedTime",
        column = @Column(name = "launch_notice_accepted_time")),
  })
  LaunchNotice launchNotice;

  /**
   * Name of first IDN table associated with TLD that matched the characters in this domain label.
   *
   * @see google.registry.tldconfig.idn.IdnLabelValidator#findValidIdnTableForTld
   */
  @IgnoreSave(IfNull.class)
  String idnTableName;

  /** Fully qualified host names of this domain's active subordinate hosts. */
  Set<String> subordinateHosts;

  /** When this domain's registration will expire. */
  DateTime registrationExpirationTime;

  /**
   * The poll message associated with this domain being deleted.
   *
   * <p>This field should be null if the domain is not in pending delete. If it is, the field should
   * refer to a {@link PollMessage} timed to when the domain is fully deleted. If the domain is
   * restored, the message should be deleted.
   */
  @Column(name = "deletion_poll_message_id")
  VKey<PollMessage.OneTime> deletePollMessage;

  /**
   * The recurring billing event associated with this domain's autorenewals.
   *
   * <p>The recurrence should be open ended unless the domain is in pending delete or fully deleted,
   * in which case it should be closed at the time the delete was requested. Whenever the domain's
   * {@link #registrationExpirationTime} is changed the recurrence should be closed, a new one
   * should be created, and this field should be updated to point to the new one.
   */
  @Column(name = "billing_recurrence_id")
  @Ignore
  VKey<BillingEvent.Recurring> autorenewBillingEvent;

  /**
   * The recurring poll message associated with this domain's autorenewals.
   *
   * <p>The recurrence should be open ended unless the domain is in pending delete or fully deleted,
   * in which case it should be closed at the time the delete was requested. Whenever the domain's
   * {@link #registrationExpirationTime} is changed the recurrence should be closed, a new one
   * should be created, and this field should be updated to point to the new one.
   */
  @Column(name = "autorenew_poll_message_id")
  VKey<PollMessage.Autorenew> autorenewPollMessage;

  /**
   * History record for the autorenew poll message.
   *
   * <p>Here so we can restore the original ofy key from sql.
   */
  @Ignore Long autorenewPollMessageHistoryId;

  /** The unexpired grace periods for this domain (some of which may not be active yet). */
  @Transient Set<GracePeriod> gracePeriods;

  /**
   * The id of the signed mark that was used to create this domain in sunrise.
   *
   * <p>Will only be populated for domains created in sunrise.
   */
  @IgnoreSave(IfNull.class)
  String smdId;

  /** Data about any pending or past transfers on this domain. */
  DomainTransferData transferData;

  /**
   * The time that this resource was last transferred.
   *
   * <p>Can be null if the resource has never been transferred.
   */
  DateTime lastTransferTime;

  /**
   * When the domain's autorenewal status will expire.
   *
   * <p>This will be {@link DateTimeUtils#END_OF_TIME} for the vast majority of domains because all
   * domains autorenew indefinitely by default and autorenew can only be countermanded by
   * administrators, typically for reasons of the URS process or termination of a registrar for
   * nonpayment.
   *
   * <p>When a domain is scheduled to not autorenew, this field is set to the current value of its
   * {@link #registrationExpirationTime}, after which point the next invocation of a periodic
   * cronjob will explicitly delete the domain. This field is a DateTime and not a boolean because
   * of edge cases that occur during the autorenew grace period. We need to be able to tell the
   * difference domains that have reached their life and must be deleted now, and domains that
   * happen to be in the autorenew grace period now but should be deleted in roughly a year.
   */
  @Index DateTime autorenewEndTime;

  /**
   * When this domain's DNS was requested to be refreshed, or null if its DNS is up-to-date.
   *
   * <p>This will almost always be null except in the couple minutes' interval between when a
   * DNS-affecting create or update operation takes place and when the {@link RefreshDnsAction}
   * runs, which resets this back to null upon completion of the DNS refresh task. This is a {@link
   * DateTime} rather than a simple dirty boolean so that the DNS refresh action can order by the
   * DNS refresh request time and take action on the oldest ones first.
   *
   * <p>Note that this is a Cloud SQL-based replacement for the {@code dns-pull} task queue. The
   * domains that have a non-null value for this field should be exactly the same as the tasks that
   * would be in the {@code dns-pull} queue. Because this is Cloud SQL-specific, it is omitted from
   * Datastore.
   *
   * <p>Note that in the {@link DomainHistory} table this value means something slightly different:
   * It means that the given domain action requested a DNS update. Unlike on the {@code Domain}
   * table, this value is not then subsequently nulled out once the DNS refresh is complete; rather,
   * it remains as a permanent record of which actions were DNS-affecting and which were not.
   */
  // TODO(mcilwain): Start using this field once we are further along in the DB migration.
  @Ignore DateTime dnsRefreshRequestTime;

  /** The {@link AllocationToken} for the package this domain is currently a part of. */
  @Nullable VKey<AllocationToken> currentPackageToken;

  /**
   * Returns the DNS refresh request time iff this domain's DNS needs refreshing, otherwise absent.
   */
  public Optional<DateTime> getDnsRefreshRequestTime() {
    return Optional.ofNullable(dnsRefreshRequestTime);
  }

  public static <T> VKey<T> restoreOfyFrom(Key<Domain> domainKey, VKey<T> key, Long historyId) {
    if (historyId == null) {
      // This is a legacy key (or a null key, in which case this works too)
      return VKey.restoreOfyFrom(key, EntityGroupRoot.class, "per-tld");
    } else {
      return VKey.restoreOfyFrom(key, domainKey, HistoryEntry.class, historyId);
    }
  }

  public ImmutableSet<String> getSubordinateHosts() {
    return nullToEmptyImmutableCopy(subordinateHosts);
  }

  public DateTime getRegistrationExpirationTime() {
    return registrationExpirationTime;
  }

  public VKey<PollMessage.OneTime> getDeletePollMessage() {
    return deletePollMessage;
  }

  public VKey<BillingEvent.Recurring> getAutorenewBillingEvent() {
    return autorenewBillingEvent;
  }

  public VKey<PollMessage.Autorenew> getAutorenewPollMessage() {
    return autorenewPollMessage;
  }

  public Long getAutorenewPollMessageHistoryId() {
    return autorenewPollMessageHistoryId;
  }

  public ImmutableSet<GracePeriod> getGracePeriods() {
    return nullToEmptyImmutableCopy(gracePeriods);
  }

  public String getSmdId() {
    return smdId;
  }

  public Optional<VKey<AllocationToken>> getCurrentPackageToken() {
    return Optional.ofNullable(currentPackageToken);
  }

  /**
   * Returns the autorenew end time if there is one, otherwise empty.
   *
   * <p>Note that {@link DateTimeUtils#END_OF_TIME} is used as a sentinel value in the database
   * representation to signify that autorenew doesn't end, and is mapped to empty here for the
   * purposes of more legible business logic.
   */
  public Optional<DateTime> getAutorenewEndTime() {
    return Optional.ofNullable(autorenewEndTime.equals(END_OF_TIME) ? null : autorenewEndTime);
  }

  @Override
  public DomainTransferData getTransferData() {
    return Optional.ofNullable(transferData).orElse(DomainTransferData.EMPTY);
  }

  @Override
  public DateTime getLastTransferTime() {
    return lastTransferTime;
  }

  @Override
  public String getForeignKey() {
    return fullyQualifiedDomainName;
  }

  public String getDomainName() {
    return fullyQualifiedDomainName;
  }

  public ImmutableSet<DelegationSignerData> getDsData() {
    return nullToEmptyImmutableCopy(dsData);
  }

  public LaunchNotice getLaunchNotice() {
    return launchNotice;
  }

  public String getIdnTableName() {
    return idnTableName;
  }

  public ImmutableSet<VKey<Host>> getNameservers() {
    return nullToEmptyImmutableCopy(nsHosts);
  }

  // Hibernate needs this in order to populate nsHosts but no one else should ever use it
  @SuppressWarnings("UnusedMethod")
  private void setNsHosts(Set<VKey<Host>> nsHosts) {
    this.nsHosts = forceEmptyToNull(nsHosts);
  }

  // Note: for the two methods below, how we wish to treat the Hibernate setters depends on the
  // current state of the object and what's passed in. The key principle is that we wish to maintain
  // the link between parent and child objects, meaning that we should keep around whichever of the
  // two sets (the parameter vs the class variable and clear/populate that as appropriate.
  //
  // If the class variable is a PersistentSet and we overwrite it here, Hibernate will throw
  // an exception "A collection with cascade=”all-delete-orphan” was no longer referenced by the
  // owning entity instance". See https://stackoverflow.com/questions/5587482 for more details.

  // Hibernate needs this in order to populate gracePeriods but no one else should ever use it
  @SuppressWarnings("UnusedMethod")
  private void setInternalGracePeriods(Set<GracePeriod> gracePeriods) {
    if (this.gracePeriods instanceof PersistentSet) {
      Set<GracePeriod> nonNullGracePeriods = nullToEmpty(gracePeriods);
      this.gracePeriods.retainAll(nonNullGracePeriods);
      this.gracePeriods.addAll(nonNullGracePeriods);
    } else {
      this.gracePeriods = gracePeriods;
    }
  }

  // Hibernate needs this in order to populate dsData but no one else should ever use it
  @SuppressWarnings("UnusedMethod")
  private void setInternalDelegationSignerData(Set<DelegationSignerData> dsData) {
    if (this.dsData instanceof PersistentSet) {
      Set<DelegationSignerData> nonNullDsData = nullToEmpty(dsData);
      this.dsData.retainAll(nonNullDsData);
      this.dsData.addAll(nonNullDsData);
    } else {
      this.dsData = dsData;
    }
  }

  public final String getCurrentSponsorRegistrarId() {
    return getPersistedCurrentSponsorRegistrarId();
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

  /** Returns the subset of grace periods having the specified type. */
  public ImmutableSet<GracePeriod> getGracePeriodsOfType(GracePeriodStatus gracePeriodType) {
    ImmutableSet.Builder<GracePeriod> builder = new ImmutableSet.Builder<>();
    for (GracePeriod gracePeriod : getGracePeriods()) {
      if (gracePeriod.getType() == gracePeriodType) {
        builder.add(gracePeriod);
      }
    }
    return builder.build();
  }

  @Override
  public DomainBase cloneProjectedAtTime(final DateTime now) {
    return cloneDomainProjectedAtTime(this, now);
  }

  /**
   * The logic in this method, which handles implicit server approval of transfers, very closely
   * parallels the logic in {@code DomainTransferApproveFlow} which handles explicit client
   * approvals.
   */
  static <T extends DomainBase> T cloneDomainProjectedAtTime(T domain, DateTime now) {
    DomainTransferData transferData = domain.getTransferData();
    DateTime transferExpirationTime = transferData.getPendingTransferExpirationTime();

    // If there's a pending transfer that has expired, handle it.
    if (TransferStatus.PENDING.equals(transferData.getTransferStatus())
        && isBeforeOrAt(transferExpirationTime, now)) {
      // Project until just before the transfer time. This will handle the case of an autorenew
      // before the transfer was even requested or during the request period.
      // If the transfer time is precisely the moment that the domain expires, there will not be an
      // autorenew billing event (since we end the recurrence at transfer time and recurrences are
      // exclusive of their ending), and we can just proceed with the transfer.
      T domainAtTransferTime =
          cloneDomainProjectedAtTime(domain, transferExpirationTime.minusMillis(1));

      DateTime expirationDate = transferData.getTransferredRegistrationExpirationTime();
      if (expirationDate == null) {
        // Extend the registration by the correct number of years from the expiration time
        // that was current on the domain right before the transfer, capped at 10 years from
        // the moment of the transfer.
        expirationDate =
            ResourceFlowUtils.computeExDateForApprovalTime(
                domainAtTransferTime, transferExpirationTime, transferData.getTransferPeriod());
      }
      // If we are within an autorenew grace period, the transfer will subsume the autorenew. There
      // will already be a cancellation written in advance by the transfer request flow, so we don't
      // need to worry about billing, but we do need to cancel out the expiration time increase.
      // The transfer period saved in the transfer data will be one year, unless the superuser
      // extension set the transfer period to zero.
      // Set the expiration, autorenew events, and grace period for the transfer. (Transfer ends
      // all other graces).
      Builder builder =
          domainAtTransferTime
              .asBuilder()
              .setRegistrationExpirationTime(expirationDate)
              // Set the speculatively-written new autorenew events as the domain's autorenew
              // events.
              .setAutorenewBillingEvent(transferData.getServerApproveAutorenewEvent())
              .setAutorenewPollMessage(
                  transferData.getServerApproveAutorenewPollMessage(),
                  transferData.getServerApproveAutorenewPollMessageHistoryId());
      if (transferData.getTransferPeriod().getValue() == 1) {
        // Set the grace period using a key to the prescheduled transfer billing event.  Not using
        // GracePeriod.forBillingEvent() here in order to avoid the actual Datastore fetch.
        builder.setGracePeriods(
            ImmutableSet.of(
                GracePeriod.create(
                    GracePeriodStatus.TRANSFER,
                    domain.getRepoId(),
                    transferExpirationTime.plus(
                        Registry.get(domain.getTld()).getTransferGracePeriodLength()),
                    transferData.getGainingRegistrarId(),
                    transferData.getServerApproveBillingEvent())));
      } else {
        // There won't be a billing event, so we don't need a grace period
        builder.setGracePeriods(ImmutableSet.of());
      }
      // Set all remaining transfer properties.
      setAutomaticTransferSuccessProperties(builder, transferData);
      builder
          .setLastEppUpdateTime(transferExpirationTime)
          .setLastEppUpdateRegistrarId(transferData.getGainingRegistrarId());
      // Finish projecting to now.
      return (T) builder.build().cloneProjectedAtTime(now);
    }

    Optional<DateTime> newLastEppUpdateTime = Optional.empty();

    // There is no transfer. Do any necessary autorenews for active domains.

    Builder builder = domain.asBuilder();
    if (isBeforeOrAt(domain.getRegistrationExpirationTime(), now)
        && END_OF_TIME.equals(domain.getDeletionTime())) {
      // Autorenew by the number of years between the old expiration time and now.
      DateTime lastAutorenewTime =
          leapSafeAddYears(
              domain.getRegistrationExpirationTime(),
              new Interval(domain.getRegistrationExpirationTime(), now).toPeriod().getYears());
      DateTime newExpirationTime = lastAutorenewTime.plusYears(1);
      builder
          .setRegistrationExpirationTime(newExpirationTime)
          .addGracePeriod(
              GracePeriod.createForRecurring(
                  GracePeriodStatus.AUTO_RENEW,
                  domain.getRepoId(),
                  lastAutorenewTime.plus(
                      Registry.get(domain.getTld()).getAutoRenewGracePeriodLength()),
                  domain.getCurrentSponsorRegistrarId(),
                  domain.getAutorenewBillingEvent()));
      newLastEppUpdateTime = Optional.of(lastAutorenewTime);
    }

    // Remove any grace periods that have expired.
    T almostBuilt = (T) builder.build();
    builder = almostBuilt.asBuilder();
    for (GracePeriod gracePeriod : almostBuilt.getGracePeriods()) {
      if (isBeforeOrAt(gracePeriod.getExpirationTime(), now)) {
        builder.removeGracePeriod(gracePeriod);
        if (!newLastEppUpdateTime.isPresent()
            || isBeforeOrAt(newLastEppUpdateTime.get(), gracePeriod.getExpirationTime())) {
          newLastEppUpdateTime = Optional.of(gracePeriod.getExpirationTime());
        }
      }
    }

    // It is possible that the lastEppUpdateClientId is different from current sponsor client
    // id, so we have to do the comparison instead of having one variable just storing the most
    // recent time.
    if (newLastEppUpdateTime.isPresent()) {
      if (domain.getLastEppUpdateTime() == null
          || newLastEppUpdateTime.get().isAfter(domain.getLastEppUpdateTime())) {
        builder
            .setLastEppUpdateTime(newLastEppUpdateTime.get())
            .setLastEppUpdateRegistrarId(domain.getCurrentSponsorRegistrarId());
      }
    }

    // Handle common properties like setting or unsetting linked status. This also handles the
    // general case of pending transfers for other resource types, but since we've always handled
    // a pending transfer by this point that's a no-op for domains.
    projectResourceOntoBuilderAtTime(almostBuilt, builder, now);
    return (T) builder.build();
  }

  /** Return what the expiration time would be if the given number of years were added to it. */
  public static DateTime extendRegistrationWithCap(
      DateTime now, DateTime currentExpirationTime, @Nullable Integer extendedRegistrationYears) {
    // We must cap registration at the max years (aka 10), even if that truncates the last year.
    return earliestOf(
        leapSafeAddYears(
            currentExpirationTime, Optional.ofNullable(extendedRegistrationYears).orElse(0)),
        leapSafeAddYears(now, MAX_REGISTRATION_YEARS));
  }

  /** Loads and returns the fully qualified host names of all linked nameservers. */
  public ImmutableSortedSet<String> loadNameserverHostNames() {
    return tm().transact(
            () ->
                tm().loadByKeys(getNameservers()).values().stream()
                    .map(Host::getHostName)
                    .collect(toImmutableSortedSet(Ordering.natural())));
  }

  /** A key to the registrant who registered this domain. */
  public VKey<ContactResource> getRegistrant() {
    return registrantContact;
  }

  public VKey<ContactResource> getAdminContact() {
    return adminContact;
  }

  public VKey<ContactResource> getBillingContact() {
    return billingContact;
  }

  public VKey<ContactResource> getTechContact() {
    return techContact;
  }

  /** Associated contacts for the domain (other than registrant). */
  public ImmutableSet<DesignatedContact> getContacts() {
    return getAllContacts(false);
  }

  public DomainAuthInfo getAuthInfo() {
    return authInfo;
  }

  /** Returns all referenced contacts from this domain. */
  public ImmutableSet<VKey<ContactResource>> getReferencedContacts() {
    return nullToEmptyImmutableCopy(getAllContacts(true)).stream()
        .map(DesignatedContact::getContactKey)
        .filter(Objects::nonNull)
        .collect(toImmutableSet());
  }

  private ImmutableSet<DesignatedContact> getAllContacts(boolean includeRegistrant) {
    ImmutableSet.Builder<DesignatedContact> builder = new ImmutableSet.Builder<>();
    if (includeRegistrant && registrantContact != null) {
      builder.add(DesignatedContact.create(DesignatedContact.Type.REGISTRANT, registrantContact));
    }
    if (adminContact != null) {
      builder.add(DesignatedContact.create(DesignatedContact.Type.ADMIN, adminContact));
    }
    if (billingContact != null) {
      builder.add(DesignatedContact.create(DesignatedContact.Type.BILLING, billingContact));
    }
    if (techContact != null) {
      builder.add(DesignatedContact.create(DesignatedContact.Type.TECH, techContact));
    }
    return builder.build();
  }

  public String getTld() {
    return tld;
  }

  /**
   * Sets the individual contact fields from {@code contacts}.
   *
   * <p>The registrant field is only set if {@code includeRegistrant} is true, as this field needs
   * to be set in some circumstances but not in others.
   */
  void setContactFields(Set<DesignatedContact> contacts, boolean includeRegistrant) {
    // Set the individual contact fields.
    billingContact = techContact = adminContact = null;
    if (includeRegistrant) {
      registrantContact = null;
    }
    HashSet<DesignatedContact.Type> contactsDiscovered = new HashSet<DesignatedContact.Type>();
    for (DesignatedContact contact : contacts) {
      checkArgument(
          !contactsDiscovered.contains(contact.getType()),
          "Duplicate contact type %s in designated contact set.",
          contact.getType());
      contactsDiscovered.add(contact.getType());
      switch (contact.getType()) {
        case BILLING:
          billingContact = contact.getContactKey();
          break;
        case TECH:
          techContact = contact.getContactKey();
          break;
        case ADMIN:
          adminContact = contact.getContactKey();
          break;
        case REGISTRANT:
          if (includeRegistrant) {
            registrantContact = contact.getContactKey();
          }
          break;
        default:
          throw new IllegalArgumentException("Unknown contact resource type: " + contact.getType());
      }
    }
  }

  @Override
  public VKey<Domain> createVKey() {
    throw new UnsupportedOperationException(
        "DomainBase is not an actual persisted entity you can create a key to; use Domain instead");
  }

  /** Predicate to determine if a given {@link DesignatedContact} is the registrant. */
  static final Predicate<DesignatedContact> IS_REGISTRANT =
      (DesignatedContact contact) -> DesignatedContact.Type.REGISTRANT.equals(contact.type);

  /** An override of {@link EppResource#asBuilder} with tighter typing. */
  @Override
  public Builder<? extends DomainBase, ?> asBuilder() {
    return new Builder<>(clone(this));
  }

  /** A builder for constructing {@link Domain}, since it is immutable. */
  public static class Builder<T extends DomainBase, B extends Builder<T, B>>
      extends EppResource.Builder<T, B> implements BuilderWithTransferData<DomainTransferData, B> {

    public Builder() {}

    Builder(T instance) {
      super(instance);
    }

    @Override
    public T build() {
      T instance = getInstance();
      // If TransferData is totally empty, set it to null.
      if (DomainTransferData.EMPTY.equals(getInstance().transferData)) {
        setTransferData(null);
      }
      // A Domain has status INACTIVE if there are no nameservers.
      if (getInstance().getNameservers().isEmpty()) {
        addStatusValue(StatusValue.INACTIVE);
      } else { // There are nameservers, so make sure INACTIVE isn't there.
        removeStatusValue(StatusValue.INACTIVE);
      }
      // If there is no autorenew end time, set it to END_OF_TIME.
      instance.autorenewEndTime = firstNonNull(getInstance().autorenewEndTime, END_OF_TIME);

      checkArgumentNotNull(emptyToNull(instance.fullyQualifiedDomainName), "Missing domainName");
      checkArgumentNotNull(instance.getRegistrant(), "Missing registrant");
      instance.tld = getTldFromDomainName(instance.fullyQualifiedDomainName);

      T newDomain = super.build();
      // Hibernate throws exception if gracePeriods or dsData is null because we enabled all
      // cascadable operations and orphan removal.
      newDomain.gracePeriods =
          newDomain.gracePeriods == null ? ImmutableSet.of() : newDomain.gracePeriods;
      newDomain.dsData =
          newDomain.dsData == null
              ? ImmutableSet.of()
              : newDomain.dsData.stream()
                  .map(ds -> ds.cloneWithDomainRepoId(instance.getRepoId()))
                  .collect(toImmutableSet());
      return newDomain;
    }

    public B setDomainName(String domainName) {
      checkArgument(
          domainName.equals(canonicalizeHostname(domainName)),
          "Domain name %s not in puny-coded, lower-case form",
          domainName);
      getInstance().fullyQualifiedDomainName = domainName;
      return thisCastToDerived();
    }

    public B setDsData(ImmutableSet<DelegationSignerData> dsData) {
      getInstance().dsData = dsData;
      getInstance().resetUpdateTimestamp();
      return thisCastToDerived();
    }

    public B setRegistrant(VKey<ContactResource> registrant) {
      // Set the registrant field specifically.
      getInstance().registrantContact = registrant;
      return thisCastToDerived();
    }

    public B setAuthInfo(DomainAuthInfo authInfo) {
      getInstance().authInfo = authInfo;
      return thisCastToDerived();
    }

    public B setNameservers(VKey<Host> nameserver) {
      getInstance().nsHosts = ImmutableSet.of(nameserver);
      getInstance().resetUpdateTimestamp();
      return thisCastToDerived();
    }

    public B setNameservers(ImmutableSet<VKey<Host>> nameservers) {
      getInstance().nsHosts = forceEmptyToNull(nameservers);
      getInstance().resetUpdateTimestamp();
      return thisCastToDerived();
    }

    public B addNameserver(VKey<Host> nameserver) {
      return addNameservers(ImmutableSet.of(nameserver));
    }

    public B addNameservers(ImmutableSet<VKey<Host>> nameservers) {
      return setNameservers(
          ImmutableSet.copyOf(Sets.union(getInstance().getNameservers(), nameservers)));
    }

    public B removeNameserver(VKey<Host> nameserver) {
      return removeNameservers(ImmutableSet.of(nameserver));
    }

    public B removeNameservers(ImmutableSet<VKey<Host>> nameservers) {
      return setNameservers(
          ImmutableSet.copyOf(difference(getInstance().getNameservers(), nameservers)));
    }

    public B setContacts(DesignatedContact contact) {
      return setContacts(ImmutableSet.of(contact));
    }

    public B setContacts(ImmutableSet<DesignatedContact> contacts) {
      checkArgument(contacts.stream().noneMatch(IS_REGISTRANT), "Registrant cannot be a contact");

      // Set the individual fields.
      getInstance().setContactFields(contacts, false);
      return thisCastToDerived();
    }

    public B addContacts(ImmutableSet<DesignatedContact> contacts) {
      return setContacts(ImmutableSet.copyOf(Sets.union(getInstance().getContacts(), contacts)));
    }

    public B removeContacts(ImmutableSet<DesignatedContact> contacts) {
      return setContacts(ImmutableSet.copyOf(difference(getInstance().getContacts(), contacts)));
    }

    public B setLaunchNotice(LaunchNotice launchNotice) {
      getInstance().launchNotice = launchNotice;
      return thisCastToDerived();
    }

    public B setIdnTableName(String idnTableName) {
      getInstance().idnTableName = idnTableName;
      return thisCastToDerived();
    }

    public B setSubordinateHosts(ImmutableSet<String> subordinateHosts) {
      getInstance().subordinateHosts = subordinateHosts;
      return thisCastToDerived();
    }

    public B addSubordinateHost(String hostToAdd) {
      return setSubordinateHosts(
          ImmutableSet.copyOf(union(getInstance().getSubordinateHosts(), hostToAdd)));
    }

    public B removeSubordinateHost(String hostToRemove) {
      return setSubordinateHosts(
          ImmutableSet.copyOf(
              CollectionUtils.difference(getInstance().getSubordinateHosts(), hostToRemove)));
    }

    public B setRegistrationExpirationTime(DateTime registrationExpirationTime) {
      getInstance().registrationExpirationTime = registrationExpirationTime;
      return thisCastToDerived();
    }

    public B setDeletePollMessage(VKey<PollMessage.OneTime> deletePollMessage) {
      getInstance().deletePollMessage = deletePollMessage;
      return thisCastToDerived();
    }

    public B setAutorenewBillingEvent(VKey<BillingEvent.Recurring> autorenewBillingEvent) {
      getInstance().autorenewBillingEvent = autorenewBillingEvent;
      return thisCastToDerived();
    }

    public B setAutorenewPollMessage(
        @Nullable VKey<PollMessage.Autorenew> autorenewPollMessage,
        @Nullable Long autorenewPollMessageHistoryId) {
      getInstance().autorenewPollMessage = autorenewPollMessage;
      getInstance().autorenewPollMessageHistoryId = autorenewPollMessageHistoryId;
      return thisCastToDerived();
    }

    public B setDnsRefreshRequestTime(Optional<DateTime> dnsRefreshRequestTime) {
      getInstance().dnsRefreshRequestTime = dnsRefreshRequestTime.orElse(null);
      return thisCastToDerived();
    }

    public B setSmdId(String smdId) {
      getInstance().smdId = smdId;
      return thisCastToDerived();
    }

    public B setGracePeriods(ImmutableSet<GracePeriod> gracePeriods) {
      getInstance().gracePeriods = gracePeriods;
      getInstance().resetUpdateTimestamp();
      return thisCastToDerived();
    }

    public B addGracePeriod(GracePeriod gracePeriod) {
      getInstance().gracePeriods = union(getInstance().getGracePeriods(), gracePeriod);
      getInstance().resetUpdateTimestamp();
      return thisCastToDerived();
    }

    public B removeGracePeriod(GracePeriod gracePeriod) {
      getInstance().gracePeriods =
          CollectionUtils.difference(getInstance().getGracePeriods(), gracePeriod);
      getInstance().resetUpdateTimestamp();
      return thisCastToDerived();
    }

    /**
     * Sets the autorenew end time, or clears it if empty is passed.
     *
     * <p>Note that {@link DateTimeUtils#END_OF_TIME} is used as a sentinel value in the database
     * representation to signify that autorenew doesn't end, and is mapped to empty here for the
     * purposes of more legible business logic.
     */
    public B setAutorenewEndTime(Optional<DateTime> autorenewEndTime) {
      getInstance().autorenewEndTime = autorenewEndTime.orElse(END_OF_TIME);
      return thisCastToDerived();
    }

    @Override
    public B setTransferData(DomainTransferData transferData) {
      getInstance().transferData = transferData;
      return thisCastToDerived();
    }

    @Override
    public B setLastTransferTime(DateTime lastTransferTime) {
      getInstance().lastTransferTime = lastTransferTime;
      return thisCastToDerived();
    }

    public B setCurrentPackageToken(@Nullable VKey<AllocationToken> currentPackageToken) {
      getInstance().currentPackageToken = currentPackageToken;
      return thisCastToDerived();
    }
  }
}
