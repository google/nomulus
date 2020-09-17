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

package google.registry.model.registry;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Predicates.equalTo;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Maps.toMap;
import static google.registry.config.RegistryConfig.getSingletonCacheRefreshDuration;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Range;
import com.google.common.net.InternetDomainName;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Mapify;
import com.googlecode.objectify.annotation.OnSave;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.Buildable;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.common.EntityGroupRoot;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.model.common.TimedTransitionProperty.TimedTransition;
import google.registry.model.domain.fee.BaseFee.FeeType;
import google.registry.model.domain.fee.Fee;
import google.registry.model.registry.label.PremiumList;
import google.registry.model.registry.label.ReservedList;
import google.registry.util.Idn;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.PostLoad;
import javax.persistence.Transient;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** Persisted per-TLD configuration data. */
@ReportedOn
@Entity
@javax.persistence.Entity(name = "Tld")
public class Registry extends ImmutableObject implements Buildable {

  @Parent @Transient Key<EntityGroupRoot> parent = getCrossTldKey();

  /**
   * The canonical string representation of the TLD associated with this {@link Registry}, which is
   * the standard ASCII for regular TLDs and punycoded ASCII for IDN TLDs.
   */
  @Id
  @javax.persistence.Id
  @Column(name = "tld_name", nullable = false)
  String tldStrId;

  /**
   * A duplicate of {@link #tldStrId}, to simplify BigQuery reporting since the id field becomes
   * {@code __key__.name} rather than being exported as a named field.
   */
  @Transient String tldStr;

  /** Sets the Datastore specific field, tldStr, when the entity is loaded from Cloud SQL */
  @PostLoad
  void postLoad() {
    tldStr = tldStrId;
  }

  /** The suffix that identifies roids as belonging to this specific tld, e.g. -HOW for .how. */
  String roidSuffix;

  /** Default values for all the relevant TLD parameters. */
  public static final TldState DEFAULT_TLD_STATE = TldState.PREDELEGATION;

  public static final boolean DEFAULT_ESCROW_ENABLED = false;
  public static final boolean DEFAULT_DNS_PAUSED = false;
  public static final Duration DEFAULT_ADD_GRACE_PERIOD = Duration.standardDays(5);
  public static final Duration DEFAULT_AUTO_RENEW_GRACE_PERIOD = Duration.standardDays(45);
  public static final Duration DEFAULT_REDEMPTION_GRACE_PERIOD = Duration.standardDays(30);
  public static final Duration DEFAULT_RENEW_GRACE_PERIOD = Duration.standardDays(5);
  public static final Duration DEFAULT_TRANSFER_GRACE_PERIOD = Duration.standardDays(5);
  public static final Duration DEFAULT_AUTOMATIC_TRANSFER_LENGTH = Duration.standardDays(5);
  public static final Duration DEFAULT_PENDING_DELETE_LENGTH = Duration.standardDays(5);
  public static final Duration DEFAULT_ANCHOR_TENANT_ADD_GRACE_PERIOD = Duration.standardDays(30);
  public static final CurrencyUnit DEFAULT_CURRENCY = USD;
  public static final Money DEFAULT_CREATE_BILLING_COST = Money.of(USD, 8);
  public static final Money DEFAULT_EAP_BILLING_COST = Money.of(USD, 0);
  public static final Money DEFAULT_RENEW_BILLING_COST = Money.of(USD, 8);
  public static final Money DEFAULT_RESTORE_BILLING_COST = Money.of(USD, 100);
  public static final Money DEFAULT_SERVER_STATUS_CHANGE_BILLING_COST = Money.of(USD, 20);

  /** The type of TLD, which determines things like backups and escrow policy. */
  public enum TldType {
    /** A real, official TLD. */
    REAL,

    /** A test TLD, for the prober. */
    TEST
  }

  /**
   * The states a TLD can be in at any given point in time. The ordering below is the required
   * sequence of states (ignoring {@link #PDT} which is a pseudo-state).
   */
  public enum TldState {

    /** The state of not yet being delegated to this registry in the root zone by IANA. */
    PREDELEGATION,

    /**
     * The state in which only trademark holders can submit a "create" request. It is identical to
     * {@link #GENERAL_AVAILABILITY} in all other respects.
     */
    START_DATE_SUNRISE,

    /**
     * A state in which no domain operations are permitted. Generally used between sunrise and
     * general availability. This state is special in that it has no ordering constraints and can
     * appear after any phase.
     */
    QUIET_PERIOD,

    /**
     * The steady state of a TLD in which all domain names are available via first-come,
     * first-serve.
     */
    GENERAL_AVAILABILITY,

    /** A "fake" state for use in predelegation testing. Acts like {@link #GENERAL_AVAILABILITY}. */
    PDT
  }

  /**
   * A transition to a TLD state at a specific time, for use in a TimedTransitionProperty. Public
   * because App Engine's security manager requires this for instantiation via reflection.
   */
  @Embed
  public static class TldStateTransition extends TimedTransition<TldState> {
    /** The TLD state. */
    private TldState tldState;

    @Override
    public TldState getValue() {
      return tldState;
    }

    @Override
    protected void setValue(TldState tldState) {
      this.tldState = tldState;
    }
  }

  /**
   * A transition to a given billing cost at a specific time, for use in a TimedTransitionProperty.
   *
   * <p>Public because App Engine's security manager requires this for instantiation via reflection.
   */
  @Embed
  public static class BillingCostTransition extends TimedTransition<Money> {
    /** The billing cost value. */
    private Money billingCost;

    @Override
    public Money getValue() {
      return billingCost;
    }

    @Override
    protected void setValue(Money billingCost) {
      this.billingCost = billingCost;
    }
  }

  /** Returns the registry for a given TLD, throwing if none exists. */
  public static Registry get(String tld) {
    Registry registry = CACHE.getUnchecked(tld).orElse(null);
    if (registry == null) {
      throw new RegistryNotFoundException(tld);
    }
    return registry;
  }

  /** Returns the registry entities for the given TLD strings, throwing if any don't exist. */
  static ImmutableSet<Registry> getAll(Set<String> tlds) {
    try {
      ImmutableMap<String, Optional<Registry>> registries = CACHE.getAll(tlds);
      ImmutableSet<String> missingRegistries =
          registries.entrySet().stream()
              .filter(e -> !e.getValue().isPresent())
              .map(Map.Entry::getKey)
              .collect(toImmutableSet());
      if (missingRegistries.isEmpty()) {
        return registries.values().stream().map(Optional::get).collect(toImmutableSet());
      } else {
        throw new RegistryNotFoundException(missingRegistries);
      }
    } catch (ExecutionException e) {
      throw new RuntimeException("Unexpected error retrieving TLDs " + tlds, e);
    }
  }

  /**
   * Invalidates the cache entry.
   *
   * <p>This is called automatically when the registry is saved. One should also call it when a
   * registry is deleted.
   */
  @OnSave
  public void invalidateInCache() {
    CACHE.invalidate(tldStr);
  }

  /** A cache that loads the {@link Registry} for a given tld. */
  private static final LoadingCache<String, Optional<Registry>> CACHE =
      CacheBuilder.newBuilder()
          .expireAfterWrite(getSingletonCacheRefreshDuration().getMillis(), MILLISECONDS)
          .build(
              new CacheLoader<String, Optional<Registry>>() {
                @Override
                public Optional<Registry> load(final String tld) {
                  // Enter a transaction-less context briefly; we don't want to enroll every TLD in
                  // a transaction that might be wrapping this call.
                  return Optional.ofNullable(
                      tm().doTransactionless(
                              () ->
                                  ofy()
                                      .load()
                                      .key(Key.create(getCrossTldKey(), Registry.class, tld))
                                      .now()));
                }

                @Override
                public Map<String, Optional<Registry>> loadAll(Iterable<? extends String> tlds) {
                  ImmutableMap<String, Key<Registry>> keysMap =
                      toMap(
                          ImmutableSet.copyOf(tlds),
                          tld -> Key.create(getCrossTldKey(), Registry.class, tld));
                  Map<Key<Registry>, Registry> entities =
                      tm().doTransactionless(() -> ofy().load().keys(keysMap.values()));
                  return Maps.transformEntries(
                      keysMap, (k, v) -> Optional.ofNullable(entities.getOrDefault(v, null)));
                }
              });

  /**
   * The name of the pricing engine that this TLD uses.
   *
   * <p>This must be a valid key for the map of pricing engines injected by {@code @Inject
   * Map<String, PricingEngine>}.
   *
   * <p>Note that it used to be the canonical class name, hence the name of this field, but this
   * restriction has since been relaxed and it may now be any unique string.
   */
  String pricingEngineClassName;

  /**
   * The set of name(s) of the {@code DnsWriter} implementations that this TLD uses.
   *
   * <p>There must be at least one entry in this set.
   *
   * <p>All entries of this list must be valid keys for the map of {@code DnsWriter}s injected by
   * <code>@Inject Map<String, DnsWriter></code>
   */
  @Column(nullable = false)
  Set<String> dnsWriters;

  /**
   * The number of locks we allow at once for {@link google.registry.dns.PublishDnsUpdatesAction}.
   *
   * <p>This should always be a positive integer- use 1 for TLD-wide locks. All {@link Registry}
   * objects have this value default to 1.
   *
   * <p>WARNING: changing this parameter changes the lock name for subsequent DNS updates, and thus
   * invalidates the locking scheme for enqueued DNS publish updates. If the {@link
   * google.registry.dns.writer.DnsWriter} you use is not parallel-write tolerant, you must follow
   * this procedure to change this value:
   *
   * <ol>
   *   <li>Pause the DNS queue via {@link google.registry.tools.UpdateTldCommand}
   *   <li>Change this number
   *   <li>Let the Registry caches expire (currently 5 minutes) and drain the DNS publish queue
   *   <li>Unpause the DNS queue
   * </ol>
   *
   * <p>Failure to do so can result in parallel writes to the {@link
   * google.registry.dns.writer.DnsWriter}, which may be dangerous depending on your implementation.
   */
  @Column(nullable = false)
  int numDnsPublishLocks;

  /** Updates an unset numDnsPublishLocks (0) to the standard default of 1. */
  void setDefaultNumDnsPublishLocks() {
    if (numDnsPublishLocks == 0) {
      numDnsPublishLocks = 1;
    }
  }

  /**
   * The unicode-aware representation of the TLD associated with this {@link Registry}.
   *
   * <p>This will be equal to {@link #tldStr} for ASCII TLDs, but will be non-ASCII for IDN TLDs. We
   * store this in a field so that it will be retained upon import into BigQuery.
   */
  @Column(nullable = false)
  String tldUnicode;

  /**
   * Id of the folder in drive used to public (export) information for this TLD.
   *
   * <p>This is optional; if not configured, then information won't be exported for this TLD.
   */
  @Nullable String driveFolderId;

  /** The type of the TLD, whether it's real or for testing. */
  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  TldType tldType = TldType.REAL;

  /**
   * Whether to enable invoicing for this TLD.
   *
   * <p>Note that this boolean is the sole determiner on whether invoices should be generated for a
   * TLD. This applies to {@link TldType#TEST} TLDs as well.
   */
  @Column(nullable = false)
  boolean invoicingEnabled = false;

  /**
   * A property that transitions to different TldStates at different times. Stored as a list of
   * TldStateTransition embedded objects using the @Mapify annotation.
   */
  @Column(nullable = false)
  @Mapify(TimedTransitionProperty.TimeMapper.class)
  TimedTransitionProperty<TldState, TldStateTransition> tldStateTransitions =
      TimedTransitionProperty.forMapify(DEFAULT_TLD_STATE, TldStateTransition.class);

  /** An automatically managed creation timestamp. */
  @Column(nullable = false)
  CreateAutoTimestamp creationTime = CreateAutoTimestamp.create(null);

  /** The set of reserved lists that are applicable to this registry. */
  @Column(name = "reserved_list_names", nullable = false)
  Set<Key<ReservedList>> reservedLists;

  /** Retrieves an ImmutableSet of all ReservedLists associated with this tld. */
  public ImmutableSet<Key<ReservedList>> getReservedLists() {
    return nullToEmptyImmutableCopy(reservedLists);
  }

  /** The static {@link PremiumList} for this TLD, if there is one. */
  @Column(name = "premium_list_name", nullable = true)
  Key<PremiumList> premiumList;

  /** Should RDE upload a nightly escrow deposit for this TLD? */
  @Column(nullable = false)
  boolean escrowEnabled = DEFAULT_ESCROW_ENABLED;

  /** Whether the pull queue that writes to authoritative DNS is paused for this TLD. */
  @Column(nullable = false)
  boolean dnsPaused = DEFAULT_DNS_PAUSED;

  /**
   * The length of the add grace period for this TLD.
   *
   * <p>Domain deletes are free and effective immediately so long as they take place within this
   * amount of time following creation.
   */
  @Column(nullable = false)
  Duration addGracePeriodLength = DEFAULT_ADD_GRACE_PERIOD;

  /** The length of the anchor tenant add grace period for this TLD. */
  @Column(nullable = false)
  Duration anchorTenantAddGracePeriodLength = DEFAULT_ANCHOR_TENANT_ADD_GRACE_PERIOD;

  /** The length of the auto renew grace period for this TLD. */
  @Column(nullable = false)
  Duration autoRenewGracePeriodLength = DEFAULT_AUTO_RENEW_GRACE_PERIOD;

  /** The length of the redemption grace period for this TLD. */
  @Column(nullable = false)
  Duration redemptionGracePeriodLength = DEFAULT_REDEMPTION_GRACE_PERIOD;

  /** The length of the renew grace period for this TLD. */
  @Column(nullable = false)
  Duration renewGracePeriodLength = DEFAULT_RENEW_GRACE_PERIOD;

  /** The length of the transfer grace period for this TLD. */
  @Column(nullable = false)
  Duration transferGracePeriodLength = DEFAULT_TRANSFER_GRACE_PERIOD;

  /** The length of time before a transfer is automatically approved for this TLD. */
  @Column(nullable = false)
  Duration automaticTransferLength = DEFAULT_AUTOMATIC_TRANSFER_LENGTH;

  /** The length of time a domain spends in the non-redeemable pending delete phase for this TLD. */
  @Column(nullable = false)
  Duration pendingDeleteLength = DEFAULT_PENDING_DELETE_LENGTH;

  /** The currency unit for all costs associated with this TLD. */
  @Column(nullable = false)
  CurrencyUnit currency = DEFAULT_CURRENCY;

  /** The per-year billing cost for registering a new domain name. */
  @AttributeOverrides({
    @AttributeOverride(
        name = "money.amount",
        column = @Column(name = "create_billing_cost_amount")),
    @AttributeOverride(
        name = "money.currency",
        column = @Column(name = "create_billing_cost_currency"))
  })
  Money createBillingCost = DEFAULT_CREATE_BILLING_COST;

  /** The one-time billing cost for restoring a domain name from the redemption grace period. */
  @AttributeOverrides({
    @AttributeOverride(
        name = "money.amount",
        column = @Column(name = "restore_billing_cost_amount")),
    @AttributeOverride(
        name = "money.currency",
        column = @Column(name = "restore_billing_cost_currency"))
  })
  Money restoreBillingCost = DEFAULT_RESTORE_BILLING_COST;

  /** The one-time billing cost for changing the server status (i.e. lock). */
  @AttributeOverrides({
    @AttributeOverride(
        name = "money.amount",
        column = @Column(name = "server_status_change_billing_cost_amount")),
    @AttributeOverride(
        name = "money.currency",
        column = @Column(name = "server_status_change_billing_cost_currency"))
  })
  Money serverStatusChangeBillingCost = DEFAULT_SERVER_STATUS_CHANGE_BILLING_COST;

  /**
   * A property that transitions to different renew billing costs at different times. Stored as a
   * list of BillingCostTransition embedded objects using the @Mapify annotation.
   *
   * <p>A given value of this property represents the per-year billing cost for renewing a domain
   * name. This cost is also used to compute costs for transfers, since each transfer includes a
   * renewal to ensure transfers have a cost.
   */
  @Column(nullable = false)
  @Mapify(TimedTransitionProperty.TimeMapper.class)
  TimedTransitionProperty<Money, BillingCostTransition> renewBillingCostTransitions =
      TimedTransitionProperty.forMapify(DEFAULT_RENEW_BILLING_COST, BillingCostTransition.class);

  /** A property that tracks the EAP fee schedule (if any) for the TLD. */
  @Column(nullable = false)
  @Mapify(TimedTransitionProperty.TimeMapper.class)
  TimedTransitionProperty<Money, BillingCostTransition> eapFeeSchedule =
      TimedTransitionProperty.forMapify(DEFAULT_EAP_BILLING_COST, BillingCostTransition.class);

  /** Marksdb LORDN service username (password is stored in Keyring) */
  String lordnUsername;

  /** The end of the claims period (at or after this time, claims no longer applies). */
  @Column(nullable = false)
  DateTime claimsPeriodEnd = END_OF_TIME;

  /** An allow list of clients allowed to be used on domains on this TLD (ignored if empty). */
  @Nullable Set<String> allowedRegistrantContactIds;

  /** An allow list of hosts allowed to be used on domains on this TLD (ignored if empty). */
  @Nullable Set<String> allowedFullyQualifiedHostNames;

  public String getTldStr() {
    return tldStr;
  }

  public String getRoidSuffix() {
    return roidSuffix;
  }

  /** Retrieve the actual domain name representing the TLD for which this registry operates. */
  public InternetDomainName getTld() {
    return InternetDomainName.from(tldStr);
  }

  /** Retrieve the TLD type (real or test). */
  public TldType getTldType() {
    return tldType;
  }

  /**
   * Retrieve the TLD state at the given time. Defaults to {@link TldState#PREDELEGATION}.
   *
   * <p>Note that {@link TldState#PDT} TLDs pretend to be in {@link TldState#GENERAL_AVAILABILITY}.
   */
  public TldState getTldState(DateTime now) {
    TldState state = tldStateTransitions.getValueAtTime(now);
    return TldState.PDT.equals(state) ? TldState.GENERAL_AVAILABILITY : state;
  }

  /** Retrieve whether this TLD is in predelegation testing. */
  public boolean isPdt(DateTime now) {
    return TldState.PDT.equals(tldStateTransitions.getValueAtTime(now));
  }

  public DateTime getCreationTime() {
    return creationTime.getTimestamp();
  }

  public boolean getEscrowEnabled() {
    return escrowEnabled;
  }

  public boolean getDnsPaused() {
    return dnsPaused;
  }

  public String getDriveFolderId() {
    return driveFolderId;
  }

  public Duration getAddGracePeriodLength() {
    return addGracePeriodLength;
  }

  public Duration getAutoRenewGracePeriodLength() {
    return autoRenewGracePeriodLength;
  }

  public Duration getRedemptionGracePeriodLength() {
    return redemptionGracePeriodLength;
  }

  public Duration getRenewGracePeriodLength() {
    return renewGracePeriodLength;
  }

  public Duration getTransferGracePeriodLength() {
    return transferGracePeriodLength;
  }

  public Duration getAutomaticTransferLength() {
    return automaticTransferLength;
  }

  public Duration getPendingDeleteLength() {
    return pendingDeleteLength;
  }

  public Duration getAnchorTenantAddGracePeriodLength() {
    return anchorTenantAddGracePeriodLength;
  }

  @Nullable
  public Key<PremiumList> getPremiumList() {
    return premiumList;
  }

  public CurrencyUnit getCurrency() {
    return currency;
  }

  /**
   * Use <code>PricingEngineProxy.getDomainCreateCost</code> instead of this to find the cost for a
   * domain create.
   */
  @VisibleForTesting
  public Money getStandardCreateCost() {
    return createBillingCost;
  }

  /**
   * Returns the add-on cost of a domain restore (the flat registry-wide fee charged in addition to
   * one year of renewal for that name).
   */
  public Money getStandardRestoreCost() {
    return restoreBillingCost;
  }

  /**
   * Use <code>PricingEngineProxy.getDomainRenewCost</code> instead of this to find the cost for a
   * domain renewal, and all derived costs (i.e. autorenews, transfers, and the per-domain part of a
   * restore cost).
   */
  public Money getStandardRenewCost(DateTime now) {
    return renewBillingCostTransitions.getValueAtTime(now);
  }

  /** Returns the cost of a server status change (i.e. lock). */
  public Money getServerStatusChangeCost() {
    return serverStatusChangeBillingCost;
  }

  public ImmutableSortedMap<DateTime, TldState> getTldStateTransitions() {
    return tldStateTransitions.toValueMap();
  }

  public ImmutableSortedMap<DateTime, Money> getRenewBillingCostTransitions() {
    return renewBillingCostTransitions.toValueMap();
  }

  /** Returns the EAP fee for the registry at the given time. */
  public Fee getEapFeeFor(DateTime now) {
    ImmutableSortedMap<DateTime, Money> valueMap = getEapFeeScheduleAsMap();
    DateTime periodStart = valueMap.floorKey(now);
    DateTime periodEnd = valueMap.ceilingKey(now);
    // NOTE: assuming END_OF_TIME would never be reached...
    Range<DateTime> validPeriod =
        Range.closedOpen(
            periodStart != null ? periodStart : START_OF_TIME,
            periodEnd != null ? periodEnd : END_OF_TIME);
    return Fee.create(
        eapFeeSchedule.getValueAtTime(now).getAmount(),
        FeeType.EAP,
        // An EAP fee does not count as premium -- it's a separate one-time fee, independent of
        // which the domain is separately considered standard vs premium depending on renewal price.
        false,
        validPeriod,
        validPeriod.upperEndpoint());
  }

  @VisibleForTesting
  public ImmutableSortedMap<DateTime, Money> getEapFeeScheduleAsMap() {
    return eapFeeSchedule.toValueMap();
  }

  public String getLordnUsername() {
    return lordnUsername;
  }

  public DateTime getClaimsPeriodEnd() {
    return claimsPeriodEnd;
  }

  public String getPremiumPricingEngineClassName() {
    return pricingEngineClassName;
  }

  public ImmutableSet<String> getDnsWriters() {
    return ImmutableSet.copyOf(dnsWriters);
  }

  /** Returns the number of simultaneous DNS publish operations we allow at once. */
  public int getNumDnsPublishLocks() {
    return numDnsPublishLocks;
  }

  public ImmutableSet<String> getAllowedRegistrantContactIds() {
    return nullToEmptyImmutableCopy(allowedRegistrantContactIds);
  }

  public ImmutableSet<String> getAllowedFullyQualifiedHostNames() {
    return nullToEmptyImmutableCopy(allowedFullyQualifiedHostNames);
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link Registry} objects, since they are immutable. */
  public static class Builder extends Buildable.Builder<Registry> {
    public Builder() {}

    private Builder(Registry instance) {
      super(instance);
    }

    public Builder setTldType(TldType tldType) {
      getInstance().tldType = tldType;
      return this;
    }

    public Builder setInvoicingEnabled(boolean invoicingEnabled) {
      getInstance().invoicingEnabled = invoicingEnabled;
      return this;
    }

    /** Sets the TLD state to transition to the specified states at the specified times. */
    public Builder setTldStateTransitions(ImmutableSortedMap<DateTime, TldState> tldStatesMap) {
      checkNotNull(tldStatesMap, "TLD states map cannot be null");
      // Filter out any entries with QUIET_PERIOD as the value before checking for ordering, since
      // that phase is allowed to appear anywhere.
      checkArgument(
          Ordering.natural()
              .isStrictlyOrdered(
                  Iterables.filter(tldStatesMap.values(), not(equalTo(TldState.QUIET_PERIOD)))),
          "The TLD states are chronologically out of order");
      getInstance().tldStateTransitions =
          TimedTransitionProperty.fromValueMap(tldStatesMap, TldStateTransition.class);
      return this;
    }

    public Builder setTldStr(String tldStr) {
      checkArgument(tldStr != null, "TLD must not be null");
      getInstance().tldStr = tldStr;
      return this;
    }

    public Builder setEscrowEnabled(boolean enabled) {
      getInstance().escrowEnabled = enabled;
      return this;
    }

    public Builder setDnsPaused(boolean paused) {
      getInstance().dnsPaused = paused;
      return this;
    }

    public Builder setDriveFolderId(String driveFolderId) {
      getInstance().driveFolderId = driveFolderId;
      return this;
    }

    public Builder setPremiumPricingEngine(String pricingEngineClass) {
      getInstance().pricingEngineClassName = checkArgumentNotNull(pricingEngineClass);
      return this;
    }

    public Builder setDnsWriters(ImmutableSet<String> dnsWriters) {
      getInstance().dnsWriters = dnsWriters;
      return this;
    }

    public Builder setNumDnsPublishLocks(int numDnsPublishLocks) {
      checkArgument(
          numDnsPublishLocks > 0,
          "numDnsPublishLocks must be positive when set explicitly (use 1 for TLD-wide locks)");
      getInstance().numDnsPublishLocks = numDnsPublishLocks;
      return this;
    }

    public Builder setAddGracePeriodLength(Duration addGracePeriodLength) {
      checkArgument(
          addGracePeriodLength.isLongerThan(Duration.ZERO),
          "addGracePeriodLength must be non-zero");
      getInstance().addGracePeriodLength = addGracePeriodLength;
      return this;
    }

    /** Warning! Changing this will affect the billing time of autorenew events in the past. */
    public Builder setAutoRenewGracePeriodLength(Duration autoRenewGracePeriodLength) {
      checkArgument(
          autoRenewGracePeriodLength.isLongerThan(Duration.ZERO),
          "autoRenewGracePeriodLength must be non-zero");
      getInstance().autoRenewGracePeriodLength = autoRenewGracePeriodLength;
      return this;
    }

    public Builder setRedemptionGracePeriodLength(Duration redemptionGracePeriodLength) {
      checkArgument(
          redemptionGracePeriodLength.isLongerThan(Duration.ZERO),
          "redemptionGracePeriodLength must be non-zero");
      getInstance().redemptionGracePeriodLength = redemptionGracePeriodLength;
      return this;
    }

    public Builder setRenewGracePeriodLength(Duration renewGracePeriodLength) {
      checkArgument(
          renewGracePeriodLength.isLongerThan(Duration.ZERO),
          "renewGracePeriodLength must be non-zero");
      getInstance().renewGracePeriodLength = renewGracePeriodLength;
      return this;
    }

    public Builder setTransferGracePeriodLength(Duration transferGracePeriodLength) {
      checkArgument(
          transferGracePeriodLength.isLongerThan(Duration.ZERO),
          "transferGracePeriodLength must be non-zero");
      getInstance().transferGracePeriodLength = transferGracePeriodLength;
      return this;
    }

    public Builder setAutomaticTransferLength(Duration automaticTransferLength) {
      checkArgument(
          automaticTransferLength.isLongerThan(Duration.ZERO),
          "automaticTransferLength must be non-zero");
      getInstance().automaticTransferLength = automaticTransferLength;
      return this;
    }

    public Builder setPendingDeleteLength(Duration pendingDeleteLength) {
      checkArgument(
          pendingDeleteLength.isLongerThan(Duration.ZERO), "pendingDeleteLength must be non-zero");
      getInstance().pendingDeleteLength = pendingDeleteLength;
      return this;
    }

    public Builder setCurrency(CurrencyUnit currency) {
      checkArgument(currency != null, "currency must be non-null");
      getInstance().currency = currency;
      return this;
    }

    public Builder setCreateBillingCost(Money amount) {
      checkArgument(amount.isPositiveOrZero(), "createBillingCost cannot be negative");
      getInstance().createBillingCost = amount;
      return this;
    }

    public Builder setReservedListsByName(Set<String> reservedListNames) {
      checkArgument(reservedListNames != null, "reservedListNames must not be null");
      ImmutableSet.Builder<ReservedList> builder = new ImmutableSet.Builder<>();
      for (String reservedListName : reservedListNames) {
        // Check for existence of the reserved list and throw an exception if it doesn't exist.
        Optional<ReservedList> reservedList = ReservedList.get(reservedListName);
        checkArgument(
            reservedList.isPresent(),
            "Could not find reserved list %s to add to the tld",
            reservedListName);
        builder.add(reservedList.get());
      }
      return setReservedLists(builder.build());
    }

    public Builder setReservedLists(ReservedList... reservedLists) {
      return setReservedLists(ImmutableSet.copyOf(reservedLists));
    }

    public Builder setReservedLists(Set<ReservedList> reservedLists) {
      checkArgumentNotNull(reservedLists, "reservedLists must not be null");
      ImmutableSet.Builder<Key<ReservedList>> builder = new ImmutableSet.Builder<>();
      for (ReservedList reservedList : reservedLists) {
        builder.add(Key.create(reservedList));
      }
      getInstance().reservedLists = builder.build();
      return this;
    }

    public Builder setPremiumList(PremiumList premiumList) {
      getInstance().premiumList = (premiumList == null) ? null : Key.create(premiumList);
      return this;
    }

    @VisibleForTesting
    public Builder setPremiumListKey(@Nullable Key<PremiumList> premiumList) {
      getInstance().premiumList = premiumList;
      return this;
    }

    public Builder setRestoreBillingCost(Money amount) {
      checkArgument(amount.isPositiveOrZero(), "restoreBillingCost cannot be negative");
      getInstance().restoreBillingCost = amount;
      return this;
    }

    /**
     * Sets the renew billing cost to transition to the specified values at the specified times.
     *
     * <p>Renew billing costs transitions should only be added at least 5 days (the length of an
     * automatic transfer) in advance, to avoid discrepancies between the cost stored with the
     * billing event (created when the transfer is requested) and the cost at the time when the
     * transfer actually occurs (5 days later).
     */
    public Builder setRenewBillingCostTransitions(
        ImmutableSortedMap<DateTime, Money> renewCostsMap) {
      checkArgumentNotNull(renewCostsMap, "Renew billing costs map cannot be null");
      checkArgument(
          renewCostsMap.values().stream().allMatch(Money::isPositiveOrZero),
          "Renew billing cost cannot be negative");
      getInstance().renewBillingCostTransitions =
          TimedTransitionProperty.fromValueMap(renewCostsMap, BillingCostTransition.class);
      return this;
    }

    /** Sets the EAP fee schedule for the TLD. */
    public Builder setEapFeeSchedule(ImmutableSortedMap<DateTime, Money> eapFeeSchedule) {
      checkArgumentNotNull(eapFeeSchedule, "EAP schedule map cannot be null");
      checkArgument(
          eapFeeSchedule.values().stream().allMatch(Money::isPositiveOrZero),
          "EAP fee cannot be negative");
      getInstance().eapFeeSchedule =
          TimedTransitionProperty.fromValueMap(eapFeeSchedule, BillingCostTransition.class);
      return this;
    }

    private static final Pattern ROID_SUFFIX_PATTERN = Pattern.compile("^[A-Z0-9_]{1,8}$");

    public Builder setRoidSuffix(String roidSuffix) {
      checkArgument(
          ROID_SUFFIX_PATTERN.matcher(roidSuffix).matches(),
          "ROID suffix must be in format %s",
          ROID_SUFFIX_PATTERN.pattern());
      getInstance().roidSuffix = roidSuffix;
      return this;
    }

    public Builder setServerStatusChangeBillingCost(Money amount) {
      checkArgument(
          amount.isPositiveOrZero(), "Server status change billing cost cannot be negative");
      getInstance().serverStatusChangeBillingCost = amount;
      return this;
    }

    public Builder setLordnUsername(String username) {
      getInstance().lordnUsername = username;
      return this;
    }

    public Builder setClaimsPeriodEnd(DateTime claimsPeriodEnd) {
      getInstance().claimsPeriodEnd = checkArgumentNotNull(claimsPeriodEnd);
      return this;
    }

    public Builder setAllowedRegistrantContactIds(
        ImmutableSet<String> allowedRegistrantContactIds) {
      getInstance().allowedRegistrantContactIds = allowedRegistrantContactIds;
      return this;
    }

    public Builder setAllowedFullyQualifiedHostNames(
        ImmutableSet<String> allowedFullyQualifiedHostNames) {
      getInstance().allowedFullyQualifiedHostNames = allowedFullyQualifiedHostNames;
      return this;
    }

    @Override
    public Registry build() {
      final Registry instance = getInstance();
      // Pick up the name of the associated TLD from the instance object.
      String tldName = instance.tldStr;
      checkArgument(tldName != null, "No registry TLD specified");
      // Check for canonical form by converting to an InternetDomainName and then back.
      checkArgument(
          InternetDomainName.isValid(tldName)
              && tldName.equals(InternetDomainName.from(tldName).toString()),
          "Cannot create registry for TLD that is not a valid, canonical domain name");
      // Check the validity of all TimedTransitionProperties to ensure that they have values for
      // START_OF_TIME.  The setters above have already checked this for new values, but also check
      // here to catch cases where we loaded an invalid TimedTransitionProperty from Datastore and
      // cloned it into a new builder, to block re-building a Registry in an invalid state.
      instance.tldStateTransitions.checkValidity();
      instance.renewBillingCostTransitions.checkValidity();
      instance.eapFeeSchedule.checkValidity();
      // All costs must be in the expected currency.
      // TODO(b/21854155): When we move PremiumList into Datastore, verify its currency too.
      checkArgument(
          instance.getStandardCreateCost().getCurrencyUnit().equals(instance.currency),
          "Create cost must be in the registry's currency");
      checkArgument(
          instance.getStandardRestoreCost().getCurrencyUnit().equals(instance.currency),
          "Restore cost must be in the registry's currency");
      checkArgument(
          instance.getServerStatusChangeCost().getCurrencyUnit().equals(instance.currency),
          "Server status change cost must be in the registry's currency");
      Predicate<Money> currencyCheck =
          (Money money) -> money.getCurrencyUnit().equals(instance.currency);
      checkArgument(
          instance.getRenewBillingCostTransitions().values().stream().allMatch(currencyCheck),
          "Renew cost must be in the registry's currency");
      checkArgument(
          instance.eapFeeSchedule.toValueMap().values().stream().allMatch(currencyCheck),
          "All EAP fees must be in the registry's currency");
      checkArgumentNotNull(
          instance.pricingEngineClassName, "All registries must have a configured pricing engine");
      checkArgument(
          instance.dnsWriters != null && !instance.dnsWriters.isEmpty(),
          "At least one DNS writer must be specified."
              + " VoidDnsWriter can be used if DNS writing isn't desired");
      // If not set explicitly, numDnsPublishLocks defaults to 1.
      instance.setDefaultNumDnsPublishLocks();
      checkArgument(
          instance.numDnsPublishLocks > 0,
          "Number of DNS publish locks must be positive. Use 1 for TLD-wide locks.");
      instance.tldStrId = tldName;
      instance.tldUnicode = Idn.toUnicode(tldName);
      return super.build();
    }
  }

  /** Exception to throw when no Registry entity is found for given TLD string(s). */
  public static class RegistryNotFoundException extends RuntimeException {
    RegistryNotFoundException(ImmutableSet<String> tlds) {
      super("No registry object(s) found for " + Joiner.on(", ").join(tlds));
    }

    RegistryNotFoundException(String tld) {
      this(ImmutableSet.of(tld));
    }
  }
}
