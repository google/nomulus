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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.gson.annotations.Expose;
import google.registry.flows.ResourceFlowUtils;
import google.registry.model.EppResource;
import google.registry.model.EppResource.ResourceWithTransferData;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenType;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.Host;
import google.registry.model.poll.PollMessage;
import google.registry.model.poll.PollMessage.Autorenew;
import google.registry.model.poll.PollMessage.OneTime;
import google.registry.model.tld.Tld;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.persistence.VKey;
import google.registry.tldconfig.idn.IdnLabelValidator;
import google.registry.tmch.LordnTaskUtils.LordnPhase;
import google.registry.tmch.NordnUploadAction;
import google.registry.util.CollectionUtils;
import google.registry.util.DateTimeUtils;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Transient;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.hibernate.collection.spi.PersistentSet;
import org.joda.time.DateTime;
import org.joda.time.Interval;

/**
 * A persistable domain resource including mutable and non-mutable fields.
 *
 * <p>This class deliberately does not include an {@link Id} so that any foreign-keyed fields can
 * refer to the proper parent entity's ID, whether we're storing this in the DB itself or as part of
 * another entity.
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
   * from (creationTime, deletionTime) there can only be one domain in the database with this name.
   * However, there can be many domains with the same name and non-overlapping lifetimes.
   *
   * <p>Invariant: domainName == domainName.toLowerCase(Locale.ENGLISH)
   */
  @Expose String domainName;

  /** The top level domain this is under, de-normalized from {@link #domainName}. */
  String tld;

  /** References to hosts that are the nameservers for the domain. */
  @Expose @Transient Set<VKey<Host>> nsHosts;

  /** Contacts keys are kept around for vestigial purposes for now. */
  @Expose @Nullable String adminContact;

  @Expose @Nullable String billingContact;
  @Expose @Nullable String techContact;
  @Expose @Nullable String registrantContact;

  /** Authorization info (aka transfer secret) of the domain. */
  @Embedded
  @AttributeOverrides({
    @AttributeOverride(name = "pw.value", column = @Column(name = "auth_info_value")),
    @AttributeOverride(name = "pw.repoId", column = @Column(name = "auth_info_repo_id")),
  })
  @Nullable
  DomainAuthInfo authInfo;

  /** Data used to construct DS records for this domain. */
  @Transient Set<DomainDsData> dsData;

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
   * @see IdnLabelValidator#findValidIdnTableForTld
   */
  String idnTableName;

  /** Fully qualified host names of this domain's active subordinate hosts. */
  @Expose Set<String> subordinateHosts;

  /** When this domain's registration will expire. */
  @Expose DateTime registrationExpirationTime;

  /**
   * The poll message associated with this domain being deleted.
   *
   * <p>This field should be null if the domain is not in pending delete. If it is, the field should
   * refer to a {@link PollMessage} timed to when the domain is fully deleted. If the domain is
   * restored, the message should be deleted.
   */
  @Column(name = "deletion_poll_message_id")
  VKey<OneTime> deletePollMessage;

  /**
   * The recurring billing event associated with this domain's autorenewals.
   *
   * <p>The recurrence should be open-ended unless the domain is in pending delete or fully deleted,
   * in which case it should be closed at the time the delete was requested. Whenever the domain's
   * {@link #registrationExpirationTime} is changed the recurrence should be closed, a new one
   * should be created, and this field should be updated to point to the new one.
   */
  @Column(name = "billing_recurrence_id")
  VKey<BillingRecurrence> autorenewBillingEvent;

  /**
   * The recurring poll message associated with this domain's autorenewals.
   *
   * <p>The recurrence should be open-ended unless the domain is in pending delete or fully deleted,
   * in which case it should be closed at the time the delete was requested. Whenever the domain's
   * {@link #registrationExpirationTime} is changed the recurrence should be closed, a new one
   * should be created, and this field should be updated to point to the new one.
   */
  @Column(name = "autorenew_poll_message_id")
  VKey<Autorenew> autorenewPollMessage;

  /** The unexpired grace periods for this domain (some of which may not be active yet). */
  @Transient Set<GracePeriod> gracePeriods;

  /**
   * The id of the signed mark that was used to create this domain in sunrise.
   *
   * <p>Will only be populated for domains created in sunrise.
   */
  String smdId;

  /** Data about any pending or past transfers on this domain. */
  DomainTransferData transferData;

  /**
   * The time that this resource was last transferred.
   *
   * <p>Can be null if the resource has never been transferred.
   */
  @Expose DateTime lastTransferTime;

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
  DateTime autorenewEndTime;

  /**
   * Which Lordn phase the domain is in after it is created but before the Nordn upload has
   * processed the domain.
   *
   * <p>This will almost always be {@code NONE} except in the interval between when a domain that
   * contains a signed mark or claims notice has been created, and when {@link NordnUploadAction}
   * runs, which includes the domain in the CSV uploaded to TMCH and sets this back to {@code NONE}.
   *
   * <p>Note that in the {@code DomainHistory} table this value means something slightly different:
   * It means that the given domain was created with a signed mark ({@code SUNRISE} or a claims
   * notice ({@code CLAIMS}. Unlike on the {@code Domain} table, this value is not then subsequently
   * set back to {@code NONE} once the Nordn upload is complete; rather, it remains as a permanent
   * record of when which phase the domain is in when created.
   */
  @Enumerated(EnumType.STRING)
  LordnPhase lordnPhase = LordnPhase.NONE;

  /**
   * The {@link AllocationToken} for the bulk pricing package this domain is currently a part of.
   */
  @Nullable
  @Column(name = "current_package_token")
  VKey<AllocationToken> currentBulkToken;

  public LordnPhase getLordnPhase() {
    return lordnPhase;
  }

  public ImmutableSet<String> getSubordinateHosts() {
    return nullToEmptyImmutableCopy(subordinateHosts);
  }

  public DateTime getRegistrationExpirationTime() {
    return registrationExpirationTime;
  }

  public VKey<OneTime> getDeletePollMessage() {
    return deletePollMessage;
  }

  public VKey<BillingRecurrence> getAutorenewBillingEvent() {
    return autorenewBillingEvent;
  }

  public VKey<Autorenew> getAutorenewPollMessage() {
    return autorenewPollMessage;
  }

  public ImmutableSet<GracePeriod> getGracePeriods() {
    return nullToEmptyImmutableCopy(gracePeriods);
  }

  public String getSmdId() {
    return smdId;
  }

  public Optional<VKey<AllocationToken>> getCurrentBulkToken() {
    return Optional.ofNullable(currentBulkToken);
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
    return domainName;
  }

  public String getDomainName() {
    return domainName;
  }

  public ImmutableSet<DomainDsData> getDsData() {
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
  @SuppressWarnings("unused")
  private void setNsHosts(Set<VKey<Host>> nsHosts) {
    this.nsHosts = forceEmptyToNull(nsHosts);
  }

  // Note: for the two methods below, how we wish to treat the Hibernate setters depends on the
  // current state of the object and what's passed in. The key principle is that we wish to maintain
  // the link between parent and child objects, meaning that we should keep around whichever of the
  // two sets (the parameter vs the class variable and clear/populate that as appropriate).
  //
  // If the class variable is a PersistentSet, and we overwrite it here, Hibernate will throw
  // an exception "A collection with cascade=”all-delete-orphan” was no longer referenced by the
  // owning entity instance". See https://stackoverflow.com/questions/5587482 for more details.

  // Hibernate needs this in order to populate gracePeriods but no one else should ever use it
  @SuppressWarnings("unused")
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
  @SuppressWarnings("unused")
  private void setInternalDelegationSignerData(Set<DomainDsData> dsData) {
    if (this.dsData instanceof PersistentSet) {
      Set<DomainDsData> nonNullDsData = nullToEmpty(dsData);
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
              .setAutorenewPollMessage(transferData.getServerApproveAutorenewPollMessage())
              .setCurrentBulkToken(null);
      if (transferData.getTransferPeriod().getValue() == 1) {
        // Set the grace period using a key to the pre-scheduled transfer billing event.  Not using
        // GracePeriod.forBillingEvent() here in order to avoid the actual fetch.
        builder.setGracePeriods(
            ImmutableSet.of(
                GracePeriod.create(
                    GracePeriodStatus.TRANSFER,
                    domain.getRepoId(),
                    transferExpirationTime.plus(
                        Tld.get(domain.getTld()).getTransferGracePeriodLength()),
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
              GracePeriod.createForRecurrence(
                  GracePeriodStatus.AUTO_RENEW,
                  domain.getRepoId(),
                  lastAutorenewTime.plus(Tld.get(domain.getTld()).getAutoRenewGracePeriodLength()),
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
        if (newLastEppUpdateTime.isEmpty()
            || isBeforeOrAt(newLastEppUpdateTime.get(), gracePeriod.getExpirationTime())) {
          newLastEppUpdateTime = Optional.of(gracePeriod.getExpirationTime());
        }
      }
    }

    // It is possible that the lastEppUpdateRegistrarId is different from current sponsor client
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
    return tm().reTransact(
            () ->
                tm().loadByKeys(getNameservers()).values().stream()
                    .map(Host::getHostName)
                    .collect(toImmutableSortedSet(Ordering.natural())));
  }

  @Nullable
  public DomainAuthInfo getAuthInfo() {
    return authInfo;
  }

  public String getTld() {
    return tld;
  }

  @Override
  public VKey<Domain> createVKey() {
    throw new UnsupportedOperationException(
        "DomainBase is not an actual persisted entity you can create a key to; use Domain instead");
  }

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

      checkArgumentNotNull(emptyToNull(instance.domainName), "Missing domainName");
      instance.tld = getTldFromDomainName(instance.domainName);

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
      getInstance().domainName = domainName;
      return thisCastToDerived();
    }

    public B setDsData(ImmutableSet<DomainDsData> dsData) {
      getInstance().dsData = dsData;
      getInstance().resetUpdateTimestamp();
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

    public B setDeletePollMessage(VKey<OneTime> deletePollMessage) {
      getInstance().deletePollMessage = deletePollMessage;
      return thisCastToDerived();
    }

    public B setAutorenewBillingEvent(VKey<BillingRecurrence> autorenewBillingEvent) {
      getInstance().autorenewBillingEvent = autorenewBillingEvent;
      return thisCastToDerived();
    }

    public B setAutorenewPollMessage(@Nullable VKey<Autorenew> autorenewPollMessage) {
      getInstance().autorenewPollMessage = autorenewPollMessage;
      return thisCastToDerived();
    }

    public B setLordnPhase(LordnPhase lordnPhase) {
      getInstance().lordnPhase = lordnPhase;
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

    public B setCurrentBulkToken(@Nullable VKey<AllocationToken> currentBulkToken) {
      if (currentBulkToken == null) {
        getInstance().currentBulkToken = currentBulkToken;
        return thisCastToDerived();
      }
      AllocationToken token =
          tm().transact(() -> tm().loadByKeyIfPresent(currentBulkToken))
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          String.format(
                              "The bulk token %s does not exist", currentBulkToken.getKey())));
      checkArgument(
          token.getTokenType().equals(TokenType.BULK_PRICING),
          "The currentBulkToken must have a BULK_PRICING TokenType");
      getInstance().currentBulkToken = currentBulkToken;
      return thisCastToDerived();
    }
  }
}
