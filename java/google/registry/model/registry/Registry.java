// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.ofy.Ofy.RECOMMENDED_MEMCACHE_EXPIRATION;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.collect.Range;
import com.google.common.net.InternetDomainName;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.Work;
import com.googlecode.objectify.annotation.Cache;
import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Mapify;
import com.googlecode.objectify.annotation.OnSave;
import com.googlecode.objectify.annotation.Parent;
import google.registry.config.RegistryEnvironment;
import google.registry.model.Buildable;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.ImmutableObject;
import google.registry.model.common.EntityGroupRoot;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.model.common.TimedTransitionProperty.TimedTransition;
import google.registry.model.domain.fee.BaseFee.FeeType;
import google.registry.model.domain.fee.Fee;
import google.registry.model.registry.label.BasePremiumList;
import google.registry.model.registry.label.ReservedList;
import google.registry.util.Idn;
import java.util.Set;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** Persisted per-TLD configuration data. */
@Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
@Entity
public class Registry extends ImmutableObject implements Buildable {

  @Parent
  Key<EntityGroupRoot> parent = getCrossTldKey();

  @Id
  /**
   * The canonical string representation of the TLD associated with this {@link Registry}, which
   * is the standard ASCII for regular TLDs and punycoded ASCII for IDN TLDs.
   */
  String tldStrId;

  /**
   * A duplicate of {@link #tldStrId}, to simplify BigQuery reporting since the id field becomes
   * {@code  __key__.name} rather than being exported as a named field.
   */
  String tldStr;

  /**
   * The suffix that identifies roids as belonging to this specific tld, e.g. -HOW for .how.
   */
  String roidSuffix;

  /** Default values for all the relevant TLD parameters. */
  public static final TldState DEFAULT_TLD_STATE = TldState.PREDELEGATION;
  public static final boolean DEFAULT_ESCROW_ENABLED = false;
  public static final boolean DEFAULT_DNS_PAUSED = false;
  public static final Duration DEFAULT_ADD_GRACE_PERIOD = Duration.standardDays(5);
  public static final Duration DEFAULT_SUNRUSH_ADD_GRACE_PERIOD = Duration.standardDays(30);
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
   * The states a TLD can be in at any given point in time.  The ordering below is the required
   * sequence of states (ignoring {@link #PDT} which is a pseudo-state).
   */
  public enum TldState {
    /** The state of not yet being delegated to this registry in the root zone by IANA. */
    PREDELEGATION,

    /**
     * The state in which only trademark holders can submit applications for domains.  Doing so
     * requires a claims notice to be submitted with the application.
     * */
    SUNRISE,

    /**
     * The state representing the overlap of {@link #SUNRISE} with a "landrush" state in which
     * anyone can submit an application for a domain name.  Sunrise applications may continue
     * during landrush, so we model the overlap as a distinct state named "sunrush".
     */
    SUNRUSH,

    /**
     * The state in which anyone can submit an application for a domain name. Sunrise applications
     * are not allowed during this phase.
     */
    LANDRUSH,

    /**
     * A state in which no domain operations are permitted.  Generally used after sunrise or
     * landrush to allocate uncontended applications and send contended applications to auction.
     * This state is special in that it has no ordering constraints and can appear after any phase.
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
   * A transition to a TLD state at a specific time, for use in a TimedTransitionProperty.
   * Public because AppEngine's security manager requires this for instantiation via reflection.
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
   * Public because AppEngine's security manager requires this for instantiation via reflection.
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

  /** A cache that loads the {@link Registry} for a given tld. */
  private static final LoadingCache<String, Optional<Registry>> CACHE = CacheBuilder.newBuilder()
        .expireAfterWrite(
            RegistryEnvironment.get().config().getSingletonCacheRefreshDuration().getMillis(),
            MILLISECONDS)
        .build(new CacheLoader<String, Optional<Registry>>() {
            @Override
            public Optional<Registry> load(final String tld) {
              // Enter a transactionless context briefly; we don't want to enroll every TLD in a
              // transaction that might be wrapping this call, and memcached results are fine here.
              return Optional.fromNullable(ofy().doTransactionless(new Work<Registry>() {
                  @Override
                  public Registry run() {
                    return ofy()
                        .load()
                        .key(Key.create(getCrossTldKey(), Registry.class, tld))
                        .now();
                  }}));
            }});

  /** Returns the registry for a given TLD, throwing if none exists. */
  public static Registry get(String tld) {
    Registry registry = CACHE.getUnchecked(tld).orNull();
    if (registry == null) {
      throw new RegistryNotFoundException(tld);
    }
    return registry;
  }

  /** Whenever a registry is saved, invalidate the cache entry. */
  @OnSave
  void updateCache() {
    CACHE.invalidate(tldStr);
  }

  /**
   * The name of the pricing engine that this TLD uses.
   *
   * <p>This must be a valid key for the map of pricing engines injected by
   * {@code @Inject Map<String, PricingEngine>}.
   *
   * <p>Note that it used to be the canonical class name, hence the name of this field, but this
   * restriction has since been relaxed and it may now be any unique string.
   */
  String pricingEngineClassName;

  /**
   * The name of the DnsWriter that this TLD uses.
   *
   * <p>This must be a valid key for the map of DnsWriters injected by <code>
   * @Inject Map<String, DnsWriter></code>
   */
  String dnsWriter;

  /**
   * The unicode-aware representation of the TLD associated with this {@link Registry}.
   *
   * <p>This will be equal to {@link #tldStr} for ASCII TLDs, but will be non-ASCII for IDN TLDs.
   * We store this in a field so that it will be retained upon import into BigQuery.
   */
  String tldUnicode;

  /** Id of the folder in drive used to publish information for this TLD. */
  String driveFolderId;

  /** The type of the TLD, whether it's real or for testing. */
  TldType tldType = TldType.REAL;

  /**
   * A property that transitions to different TldStates at different times. Stored as a list of
   * TldStateTransition embedded objects using the @Mapify annotation.
   */
  @Mapify(TimedTransitionProperty.TimeMapper.class)
  TimedTransitionProperty<TldState, TldStateTransition> tldStateTransitions =
      TimedTransitionProperty.forMapify(DEFAULT_TLD_STATE, TldStateTransition.class);

  /** An automatically managed creation timestamp. */
  CreateAutoTimestamp creationTime = CreateAutoTimestamp.create(null);

  /** The set of reserved lists that are applicable to this registry. */
  Set<Key<ReservedList>> reservedLists;

  /** Retrieves an ImmutableSet of all ReservedLists associated with this tld. */
  public ImmutableSet<Key<ReservedList>> getReservedLists() {
    return nullToEmptyImmutableCopy(reservedLists);
  }

  /** The static {@link BasePremiumList} for this TLD, if there is one. */
  Key<BasePremiumList> premiumList;

  /** Should RDE upload a nightly escrow deposit for this TLD? */
  boolean escrowEnabled = DEFAULT_ESCROW_ENABLED;

  /** Whether the pull queue that writes to authoritative DNS is paused for this TLD. */
  boolean dnsPaused = DEFAULT_DNS_PAUSED;

  /** Whether the price must be acknowledged to register premiun names on this TLD. */
  boolean premiumPriceAckRequired = true;

  /** The length of the add grace period for this TLD. */
  Duration addGracePeriodLength = DEFAULT_ADD_GRACE_PERIOD;

  /** The length of the add grace period for this TLD. */
  Duration anchorTenantAddGracePeriodLength = DEFAULT_ANCHOR_TENANT_ADD_GRACE_PERIOD;

  /** The length of the sunrush add grace period for this TLD. */
  Duration sunrushAddGracePeriodLength = DEFAULT_SUNRUSH_ADD_GRACE_PERIOD;

  /** The length of the auto renew grace period for this TLD. */
  Duration autoRenewGracePeriodLength = DEFAULT_AUTO_RENEW_GRACE_PERIOD;

  /** The length of the redemption grace period for this TLD. */
  Duration redemptionGracePeriodLength = DEFAULT_REDEMPTION_GRACE_PERIOD;

  /** The length of the renew grace period for this TLD. */
  Duration renewGracePeriodLength = DEFAULT_RENEW_GRACE_PERIOD;

  /** The length of the transfer grace period for this TLD. */
  Duration transferGracePeriodLength = DEFAULT_TRANSFER_GRACE_PERIOD;

  /** The length of time before a transfer is automatically approved for this TLD. */
  Duration automaticTransferLength = DEFAULT_AUTOMATIC_TRANSFER_LENGTH;

  /** The length of time a domain spends in the non-redeemable pending delete phase for this TLD. */
  Duration pendingDeleteLength = DEFAULT_PENDING_DELETE_LENGTH;

  /** The currency unit for all costs associated with this TLD. */
  CurrencyUnit currency = DEFAULT_CURRENCY;

  /** The per-year billing cost for registering a new domain name. */
  Money createBillingCost = DEFAULT_CREATE_BILLING_COST;

  /** The one-time billing cost for restoring a domain name from the redemption grace period. */
  Money restoreBillingCost = DEFAULT_RESTORE_BILLING_COST;

  /** The one-time billing cost for changing the server status (i.e. lock). */
  Money serverStatusChangeBillingCost = DEFAULT_SERVER_STATUS_CHANGE_BILLING_COST;

  /**
   * A property that transitions to different renew billing costs at different times. Stored as a
   * list of BillingCostTransition embedded objects using the @Mapify annotation.
   *
   * <p>A given value of this property represents the per-year billing cost for renewing a domain
   * name.  This cost is also used to compute costs for transfers, since each transfer includes a
   * renewal to ensure transfers have a cost.
   */
  @Mapify(TimedTransitionProperty.TimeMapper.class)
  TimedTransitionProperty<Money, BillingCostTransition> renewBillingCostTransitions =
      TimedTransitionProperty.forMapify(DEFAULT_RENEW_BILLING_COST, BillingCostTransition.class);

  /**
   * A property that tracks the EAP fee schedule (if any) for the TLD.
   */
  @Mapify(TimedTransitionProperty.TimeMapper.class)
  TimedTransitionProperty<Money, BillingCostTransition> eapFeeSchedule =
      TimedTransitionProperty.forMapify(DEFAULT_EAP_BILLING_COST, BillingCostTransition.class);

  String lordnUsername;

  /** The end of the claims period (at or after this time, claims no longer applies). */
  DateTime claimsPeriodEnd = END_OF_TIME;

  /** A whitelist of clients allowed to be used on domains on this TLD (ignored if empty). */
  Set<String> allowedRegistrantContactIds;

  /** A whitelist of hosts allowed to be used on domains on this TLD (ignored if empty). */
  Set<String> allowedFullyQualifiedHostNames;

  /** The set of {@link TldState}s for which LRP applications are accepted (ignored if empty). */
  Set<TldState> lrpTldStates;

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
   * Retrieve the TLD state at the given time.  Defaults to {@link TldState#PREDELEGATION}.
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

  public boolean getPremiumPriceAckRequired() {
    return premiumPriceAckRequired;
  }

  public Duration getAddGracePeriodLength() {
    return addGracePeriodLength;
  }

  public Duration getSunrushAddGracePeriodLength() {
    return sunrushAddGracePeriodLength;
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

  public Key<BasePremiumList> getPremiumList() {
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
  @VisibleForTesting
  public Money getStandardRenewCost(DateTime now) {
    return renewBillingCostTransitions.getValueAtTime(now);
  }

  /**
   * Returns the cost of a server status change (i.e. lock).
   */
  public Money getServerStatusChangeCost() {
    return serverStatusChangeBillingCost;
  }

  public ImmutableSortedMap<DateTime, TldState> getTldStateTransitions() {
    return tldStateTransitions.toValueMap();
  }

  public ImmutableSortedMap<DateTime, Money> getRenewBillingCostTransitions() {
    return renewBillingCostTransitions.toValueMap();
  }

  /**
   * Returns the EAP fee for the registry at the given time.
   */
  public Fee getEapFeeFor(DateTime now) {
    ImmutableSortedMap<DateTime, Money> valueMap = eapFeeSchedule.toValueMap();
    DateTime periodStart = valueMap.floorKey(now);
    DateTime periodEnd = valueMap.ceilingKey(now);
    // NOTE: assuming END_OF_TIME would never be reached...
    Range<DateTime> validPeriod = Range.closedOpen(
        periodStart != null ? periodStart : START_OF_TIME,
            periodEnd != null ? periodEnd : END_OF_TIME);
    return Fee.create(
        eapFeeSchedule.getValueAtTime(now).getAmount(),
        FeeType.EAP,
        validPeriod,
        validPeriod.upperEndpoint());
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

  public String getDnsWriter() {
    return dnsWriter;
  }

  public ImmutableSet<String> getAllowedRegistrantContactIds() {
    return nullToEmptyImmutableCopy(allowedRegistrantContactIds);
  }

  public ImmutableSet<String> getAllowedFullyQualifiedHostNames() {
    return nullToEmptyImmutableCopy(allowedFullyQualifiedHostNames);
  }

  public ImmutableSet<TldState> getLrpTldStates() {
    return nullToEmptyImmutableCopy(lrpTldStates);
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

    /** Sets the TLD state to transition to the specified states at the specified times. */
    public Builder setTldStateTransitions(
        ImmutableSortedMap<DateTime, TldState> tldStatesMap) {
      checkNotNull(tldStatesMap, "TLD states map cannot be null");
      // Filter out any entries with QUIET_PERIOD as the value before checking for ordering, since
      // that phase is allowed to appear anywhere.
      checkArgument(Ordering.natural().isStrictlyOrdered(
          Iterables.filter(tldStatesMap.values(), not(equalTo(TldState.QUIET_PERIOD)))),
          "The TLD states are chronologically out of order");
      getInstance().tldStateTransitions =
          TimedTransitionProperty.fromValueMap(tldStatesMap, TldStateTransition.class);
      return this;
    }

    public Builder setTldStr(String tldStr) {
      checkArgument(tldStr != null, "TLD must not be null.");
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

    public Builder setPremiumPriceAckRequired(boolean premiumPriceAckRequired) {
      getInstance().premiumPriceAckRequired = premiumPriceAckRequired;
      return this;
    }

    public Builder setPremiumPricingEngine(String pricingEngineClass) {
      getInstance().pricingEngineClassName = checkArgumentNotNull(pricingEngineClass);
      return this;
    }

    public Builder setDnsWriter(String dnsWriter) {
      getInstance().dnsWriter = checkArgumentNotNull(dnsWriter);
      return this;
    }


    public Builder setAddGracePeriodLength(Duration addGracePeriodLength) {
      checkArgument(addGracePeriodLength.isLongerThan(Duration.ZERO),
          "addGracePeriodLength must be non-zero");
      getInstance().addGracePeriodLength = addGracePeriodLength;
      return this;
    }

    public Builder setSunrushAddGracePeriodLength(Duration sunrushAddGracePeriodLength) {
      checkArgument(sunrushAddGracePeriodLength.isLongerThan(Duration.ZERO),
          "sunrushAddGracePeriodLength must be non-zero");
      getInstance().sunrushAddGracePeriodLength = sunrushAddGracePeriodLength;
      return this;
    }

    /** Warning! Changing this will affect the billing time of autorenew events in the past. */
    public Builder setAutoRenewGracePeriodLength(Duration autoRenewGracePeriodLength) {
      checkArgument(autoRenewGracePeriodLength.isLongerThan(Duration.ZERO),
          "autoRenewGracePeriodLength must be non-zero");
      getInstance().autoRenewGracePeriodLength = autoRenewGracePeriodLength;
      return this;
    }

    public Builder setRedemptionGracePeriodLength(Duration redemptionGracePeriodLength) {
      checkArgument(redemptionGracePeriodLength.isLongerThan(Duration.ZERO),
          "redemptionGracePeriodLength must be non-zero");
      getInstance().redemptionGracePeriodLength = redemptionGracePeriodLength;
      return this;
    }

    public Builder setRenewGracePeriodLength(Duration renewGracePeriodLength) {
      checkArgument(renewGracePeriodLength.isLongerThan(Duration.ZERO),
          "renewGracePeriodLength must be non-zero");
      getInstance().renewGracePeriodLength = renewGracePeriodLength;
      return this;
    }

    public Builder setTransferGracePeriodLength(Duration transferGracePeriodLength) {
      checkArgument(transferGracePeriodLength.isLongerThan(Duration.ZERO),
          "transferGracePeriodLength must be non-zero");
      getInstance().transferGracePeriodLength = transferGracePeriodLength;
      return this;
    }

    public Builder setAutomaticTransferLength(Duration automaticTransferLength) {
      checkArgument(automaticTransferLength.isLongerThan(Duration.ZERO),
          "automaticTransferLength must be non-zero");
      getInstance().automaticTransferLength = automaticTransferLength;
      return this;
    }

    public Builder setPendingDeleteLength(Duration pendingDeleteLength) {
      checkArgument(pendingDeleteLength.isLongerThan(Duration.ZERO),
          "pendingDeleteLength must be non-zero");
      getInstance().pendingDeleteLength = pendingDeleteLength;
      return this;
    }

    public Builder setCurrency(CurrencyUnit currency) {
      checkArgument(currency != null, "currency must be non-null");
      getInstance().currency = currency;
      return this;
    }

    public Builder setCreateBillingCost(Money amount) {
      checkArgument(amount.isPositiveOrZero(), "create billing cost cannot be negative");
      getInstance().createBillingCost = amount;
      return this;
    }

    public Builder setReservedListsByName(Set<String> reservedListNames) {
      checkArgument(reservedListNames != null, "reservedListNames must not be null");
      ImmutableSet.Builder<Key<ReservedList>> builder = new ImmutableSet.Builder<>();
      for (String reservedListName : reservedListNames) {
        // Check for existence of the reserved list and throw an exception if it doesn't exist.
        Optional<ReservedList> reservedList = ReservedList.get(reservedListName);
        if (!reservedList.isPresent()) {
          throw new IllegalStateException(
              "Could not find reserved list " + reservedListName + " to add to the tld");
        }
        builder.add(Key.create(reservedList.get()));
      }
      getInstance().reservedLists = builder.build();
      return this;
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

    public Builder setPremiumList(BasePremiumList premiumList) {
      getInstance().premiumList = (premiumList == null) ? null : Key.create(premiumList);
      return this;
    }

    public Builder setRestoreBillingCost(Money amount) {
      checkArgument(amount.isPositiveOrZero(), "restore billing cost cannot be negative");
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
      checkArgumentNotNull(renewCostsMap, "renew billing costs map cannot be null");
      checkArgument(Iterables.all(
          renewCostsMap.values(),
          new Predicate<Money>() {
            @Override
            public boolean apply(Money amount) {
              return amount.isPositiveOrZero();
            }}),
          "renew billing cost cannot be negative");
      getInstance().renewBillingCostTransitions =
          TimedTransitionProperty.fromValueMap(renewCostsMap, BillingCostTransition.class);
      return this;
    }

    /**
     * Sets the EAP fee schedule for the TLD.
     */
    public Builder setEapFeeSchedule(
        ImmutableSortedMap<DateTime, Money> eapFeeSchedule) {
      checkArgumentNotNull(eapFeeSchedule, "EAP schedule map cannot be null");
      checkArgument(Iterables.all(
          eapFeeSchedule.values(),
          new Predicate<Money>() {
            @Override
            public boolean apply(Money amount) {
              return amount.isPositiveOrZero();
            }}),
          "EAP fee cannot be negative");
      getInstance().eapFeeSchedule =
          TimedTransitionProperty.fromValueMap(eapFeeSchedule, BillingCostTransition.class);
      return this;
    }

    public Builder setRoidSuffix(String roidSuffix) {
      getInstance().roidSuffix = roidSuffix;
      return this;
    }

    public Builder setServerStatusChangeBillingCost(Money amount) {
      checkArgument(
          amount.isPositiveOrZero(), "server status change billing cost cannot be negative");
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

    public Builder setLrpTldStates(ImmutableSet<TldState> lrpTldStates) {
      getInstance().lrpTldStates = lrpTldStates;
      return this;
    }

    @Override
    public Registry build() {
      final Registry instance = getInstance();
      // Pick up the name of the associated TLD from the instance object.
      String tldName = instance.tldStr;
      checkArgument(tldName != null, "No registry TLD specified.");
      // Check for canonical form by converting to an InternetDomainName and then back.
      checkArgument(
          InternetDomainName.isValid(tldName)
              && tldName.equals(InternetDomainName.from(tldName).toString()),
          "Cannot create registry for TLD that is not a valid, canonical domain name");
      // Check the validity of all TimedTransitionProperties to ensure that they have values for
      // START_OF_TIME.  The setters above have already checked this for new values, but also check
      // here to catch cases where we loaded an invalid TimedTransitionProperty from datastore and
      // cloned it into a new builder, to block re-building a Registry in an invalid state.
      instance.tldStateTransitions.checkValidity();
      instance.renewBillingCostTransitions.checkValidity();
      checkArgument(
          instance.tldStateTransitions.toValueMap().values()
              .containsAll(instance.getLrpTldStates()),
          "Cannot specify an LRP TLD state that is not part of the TLD state transitions.");
      instance.eapFeeSchedule.checkValidity();
      // All costs must be in the expected currency.
      // TODO(b/21854155): When we move PremiumList into datastore, verify its currency too.
      checkArgument(
          instance.getStandardCreateCost().getCurrencyUnit().equals(instance.currency),
          "Create cost must be in the registry's currency");
      checkArgument(
          instance.getStandardRestoreCost().getCurrencyUnit().equals(instance.currency),
          "Restore cost must be in the registry's currency");
      checkArgument(
          instance.getServerStatusChangeCost().getCurrencyUnit().equals(instance.currency),
          "Server status change cost must be in the registry's currency");
      Predicate<Money> currencyCheck = new Predicate<Money>(){
          @Override
          public boolean apply(Money money) {
            return money.getCurrencyUnit().equals(instance.currency);
          }};
      checkArgument(
          Iterables.all(
              instance.getRenewBillingCostTransitions().values(), currencyCheck),
          "Renew cost must be in the registry's currency");
      checkArgument(
          Iterables.all(
              instance.eapFeeSchedule.toValueMap().values(), currencyCheck),
          "All EAP fees must be in the registry's currency");
      checkArgumentNotNull(
          instance.pricingEngineClassName, "All registries must have a configured pricing engine");
      instance.tldStrId = tldName;
      instance.tldUnicode = Idn.toUnicode(tldName);
      return super.build();
    }
  }

  /** Exception to throw when no Registry is found for a given tld. */
  public static class RegistryNotFoundException extends RuntimeException{
    RegistryNotFoundException(String tld) {
      super("No registry object found for " + tld);
    }
  }
}
