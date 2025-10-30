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

package google.registry.model.tld;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.config.RegistryConfig.getSingletonCacheRefreshDuration;
import static google.registry.model.EntityYamlUtils.createObjectMapper;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static org.joda.money.CurrencyUnit.USD;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Range;
import com.google.common.net.InternetDomainName;
import google.registry.model.Buildable;
import google.registry.model.CacheUtils;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.EntityYamlUtils.CreateAutoTimestampDeserializer;
import google.registry.model.EntityYamlUtils.CurrencyDeserializer;
import google.registry.model.EntityYamlUtils.CurrencySerializer;
import google.registry.model.EntityYamlUtils.OptionalDurationSerializer;
import google.registry.model.EntityYamlUtils.OptionalStringSerializer;
import google.registry.model.EntityYamlUtils.SortedEnumSetSerializer;
import google.registry.model.EntityYamlUtils.SortedSetSerializer;
import google.registry.model.EntityYamlUtils.TimedTransitionPropertyMoneyDeserializer;
import google.registry.model.EntityYamlUtils.TimedTransitionPropertyTldStateDeserializer;
import google.registry.model.EntityYamlUtils.TokenVKeyListDeserializer;
import google.registry.model.EntityYamlUtils.TokenVKeyListSerializer;
import google.registry.model.ImmutableObject;
import google.registry.model.UnsafeSerializable;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.model.domain.fee.BaseFee.FeeType;
import google.registry.model.domain.fee.Fee;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenType;
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.ReservedList;
import google.registry.persistence.VKey;
import google.registry.persistence.converter.AllocationTokenVkeyListUserType;
import google.registry.persistence.converter.BillingCostTransitionUserType;
import google.registry.persistence.converter.TldStateTransitionUserType;
import google.registry.tldconfig.idn.IdnTableEnum;
import google.registry.util.Idn;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostPersist;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.hibernate.annotations.Type;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** Persisted per-TLD configuration data. */
@Entity
public class Tld extends ImmutableObject implements Buildable, UnsafeSerializable {

  /**
   * The canonical string representation of the TLD associated with this {@link Tld}, which is the
   * standard ASCII for regular TLDs and punycoded ASCII for IDN TLDs.
   */
  @Id
  @Column(name = "tld_name", nullable = false)
  String tldStr;

  /** The suffix that identifies roids as belonging to this specific tld, e.g., -HOW for .how. */
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
  public static final Money DEFAULT_REGISTRY_LOCK_OR_UNLOCK_BILLING_COST = Money.of(USD, 0);

  public boolean equalYaml(Tld tldToCompare) {
    if (this.equals(tldToCompare)) {
      return true;
    }
    ObjectMapper mapper = createObjectMapper();
    try {
      String thisYaml = mapper.writeValueAsString(this);
      String otherYaml = mapper.writeValueAsString(tldToCompare);
      return thisYaml.equals(otherYaml);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  /** The type of TLD, which determines things like backups and escrow policy. */
  public enum TldType {
    /**
     * A real, official TLD (but not necessarily only on production).
     *
     * <p>Note that, to avoid unnecessary costly DB writes, {@link
     * google.registry.model.reporting.DomainTransactionRecord}s are only written out for REAL TLDs
     * (these transaction records are only used for ICANN reporting purposes).
     */
    REAL,

    /** A test TLD, for the prober, OT&amp;E, and other testing purposes. */
    TEST
  }

  /**
   * The states a TLD can be in at any given point in time. The ordering below is the required
   * sequence of states (ignoring {@link #PDT} which is a pseudo-state).
   */
  public enum TldState {

    /** The state of not yet being delegated to this TLD in the root zone by IANA. */
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

  /** Returns the TLD for a given TLD, throwing if none exists. */
  public static Tld get(String tld) {
    return CACHE.get(tld).orElseThrow(() -> new TldNotFoundException(tld));
  }

  /** Returns the TLD entities for the given TLD strings, throwing if any don't exist. */
  public static ImmutableSet<Tld> get(Set<String> tlds) {
    Map<String, Optional<Tld>> tldObjects = CACHE.getAll(tlds);
    ImmutableSet<String> missingTlds =
        tldObjects.entrySet().stream()
            .filter(e -> e.getValue().isEmpty())
            .map(Map.Entry::getKey)
            .collect(toImmutableSet());
    if (missingTlds.isEmpty()) {
      return tldObjects.values().stream().map(Optional::get).collect(toImmutableSet());
    } else {
      throw new TldNotFoundException(missingTlds);
    }
  }

  /**
   * Invalidates the cache entry.
   *
   * <p>This is called automatically when the tld is saved. One should also call it when a tld is
   * deleted.
   */
  @PostPersist
  public void invalidateInCache() {
    CACHE.invalidate(tldStr);
  }

  /** A cache that loads the {@link Tld} for a given tld. */
  private static final LoadingCache<String, Optional<Tld>> CACHE =
      CacheUtils.newCacheBuilder(getSingletonCacheRefreshDuration())
          .build(
              new CacheLoader<>() {
                @Override
                public Optional<Tld> load(final String tld) {
                  return tm().reTransact(() -> tm().loadByKeyIfPresent(createVKey(tld)));
                }

                @Override
                public Map<? extends String, ? extends Optional<Tld>> loadAll(
                    Set<? extends String> tlds) {
                  ImmutableMap<String, VKey<Tld>> keysMap =
                      tlds.stream().collect(toImmutableMap(tld -> tld, Tld::createVKey));
                  Map<VKey<? extends Tld>, Tld> entities =
                      tm().reTransact(() -> tm().loadByKeysIfPresent(keysMap.values()));
                  return Maps.toMap(
                      tlds, tld -> Optional.ofNullable(entities.get(createVKey(tld))));
                }
              });

  public static VKey<Tld> createVKey(String tld) {
    return VKey.create(Tld.class, tld);
  }

  @Override
  public VKey<Tld> createVKey() {
    return VKey.create(Tld.class, tldStr);
  }

  /** Checks if {@code tld} is enrolled with BSA. */
  public static boolean isEnrolledWithBsa(Tld tld, DateTime now) {
    return tld.getBsaEnrollStartTime().orElse(END_OF_TIME).isBefore(now);
  }

  /**
   * The name of the pricing engine that this TLD uses.
   *
   * <p>This must be a valid key for the map of pricing engines injected by {@code @Inject
   * Map<String, PricingEngine>}.
   *
   * <p>Note that it used to be the canonical class name, hence the name of this field, but this
   * restriction has since been relaxed, and it may now be any unique string.
   */
  String pricingEngineClassName;

  /**
   * The set of name(s) of the {@code DnsWriter} implementations that this TLD uses.
   *
   * <p>There must be at least one entry in this set.
   *
   * <p>All entries of this list must be valid keys for the map of {@code DnsWriter}s injected by
   * {@code @Inject Map<String, DnsWriter>}
   */
  @JsonSerialize(using = SortedSetSerializer.class)
  @Column(nullable = false)
  Set<String> dnsWriters;

  /**
   * The number of locks we allow at once for {@link google.registry.dns.PublishDnsUpdatesAction}.
   *
   * <p>This should always be a positive integer -- use 1 for TLD-wide locks. All {@link Tld}
   * objects have this value default to 1.
   *
   * <p>WARNING: changing this parameter changes the lock name for subsequent DNS updates, and thus
   * invalidates the locking scheme for enqueued DNS publish updates. If the {@link
   * google.registry.dns.writer.DnsWriter} you use is not parallel-write tolerant, you must follow
   * this procedure to change this value:
   *
   * <ol>
   *   <li>Pause the DNS queue via {@link google.registry.tools.ConfigureTldCommand}
   *   <li>Change this number
   *   <li>Let the Tld caches expire (currently 5 minutes) and drain the DNS publish queue
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
   * The time to live for DNS A and AAAA records.
   *
   * <p>When this field is null, the "dnsDefaultATtl" value from the config file will be used.
   */
  @JsonSerialize(using = OptionalDurationSerializer.class)
  Duration dnsAPlusAaaaTtl;

  /**
   * The time to live for DNS NS records.
   *
   * <p>When this field is null, the "dnsDefaultNsTtl" value from the config file will be used.
   */
  @JsonSerialize(using = OptionalDurationSerializer.class)
  Duration dnsNsTtl;

  /**
   * The time to live for DNS DS records.
   *
   * <p>When this field is null, the "dnsDefaultDsTtl" value from the config file will be used.
   */
  @JsonSerialize(using = OptionalDurationSerializer.class)
  Duration dnsDsTtl;

  /**
   * The unicode-aware representation of the TLD associated with this {@link Tld}.
   *
   * <p>This will be equal to {@link #tldStr} for ASCII TLDs, but will be non-ASCII for IDN TLDs. We
   * store this in a field so that it will be retained upon import into BigQuery.
   */
  @Column(nullable = false)
  String tldUnicode;

  /**
   * ID of the folder in drive used to public (export) information for this TLD.
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
  boolean invoicingEnabled;

  /** A property that transitions to different {@link TldState}s at different times. */
  @Column(nullable = false)
  @Type(TldStateTransitionUserType.class)
  @JsonDeserialize(using = TimedTransitionPropertyTldStateDeserializer.class)
  TimedTransitionProperty<TldState> tldStateTransitions =
      TimedTransitionProperty.withInitialValue(DEFAULT_TLD_STATE);

  /** An automatically managed creation timestamp. */
  @Column(nullable = false)
  @JsonDeserialize(using = CreateAutoTimestampDeserializer.class)
  CreateAutoTimestamp creationTime = CreateAutoTimestamp.create(null);

  /** The set of reserved list names that are applicable to this tld. */
  @JsonSerialize(using = SortedSetSerializer.class)
  @Column(name = "reserved_list_names")
  Set<String> reservedListNames;

  /**
   * Retrieves an ImmutableSet of all ReservedLists associated with this TLD.
   *
   * <p>This set contains only the names of the list and not a reference to the lists. Updates to a
   * reserved list in Cloud SQL are saved as a new ReservedList entity. When using the ReservedList
   * for a tld, the database should be queried for the entity with this name that has the largest
   * revision ID.
   */
  public ImmutableSet<String> getReservedListNames() {
    return nullToEmptyImmutableCopy(reservedListNames);
  }

  /**
   * The name of the {@link PremiumList} for this TLD, if there is one.
   *
   * <p>This is only the name of the list and not a reference to the list. Updates to the premium
   * list in Cloud SQL are saved as a new PremiumList entity. When using the PremiumList for a tld,
   * the database should be queried for the entity with this name that has the largest revision ID.
   */
  @Column(name = "premium_list_name")
  @JsonSerialize(using = OptionalStringSerializer.class)
  String premiumListName;

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

  /** The length of the autorenew grace period for this TLD. */
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
  @JsonSerialize(using = CurrencySerializer.class)
  @JsonDeserialize(using = CurrencyDeserializer.class)
  CurrencyUnit currency = DEFAULT_CURRENCY;

  /** A property that transitions to different create billing costs at different times. */
  @Column(nullable = false)
  @Type(BillingCostTransitionUserType.class)
  @JsonDeserialize(using = TimedTransitionPropertyMoneyDeserializer.class)
  TimedTransitionProperty<Money> createBillingCostTransitions =
      TimedTransitionProperty.withInitialValue(DEFAULT_CREATE_BILLING_COST);

  /** The one-time billing cost for restoring a domain name from the redemption grace period. */
  @AttributeOverride(
      name = "amount",
      // Override Hibernate default (numeric(38,2)) to match real schema definition (numeric(19,2)).
      column = @Column(name = "restore_billing_cost_amount", precision = 19, scale = 2))
  @AttributeOverride(name = "currency", column = @Column(name = "restore_billing_cost_currency"))
  Money restoreBillingCost = DEFAULT_RESTORE_BILLING_COST;

  /** The one-time billing cost for changing the server status (i.e. lock). */
  @AttributeOverride(
      name = "amount",
      // Override Hibernate default (numeric(38,2)) to match real schema definition (numeric(19,2)).
      column =
          @Column(name = "server_status_change_billing_cost_amount", precision = 19, scale = 2))
  @AttributeOverride(
      name = "currency",
      column = @Column(name = "server_status_change_billing_cost_currency"))
  Money serverStatusChangeBillingCost = DEFAULT_SERVER_STATUS_CHANGE_BILLING_COST;

  /** The one-time billing cost for a registry lock/unlock action initiated by a registrar. */
  @AttributeOverride(
      name = "amount",
      // Override Hibernate default (numeric(38,2)) to match real schema definition (numeric(19,2)).
      column = @Column(name = "registry_lock_or_unlock_cost_amount", precision = 19, scale = 2))
  @AttributeOverride(
      name = "currency",
      column = @Column(name = "registry_lock_or_unlock_cost_currency"))
  Money registryLockOrUnlockBillingCost = DEFAULT_REGISTRY_LOCK_OR_UNLOCK_BILLING_COST;

  /**
   * A property that transitions to different renew billing costs at different times.
   *
   * <p>A given value of this property represents the per-year billing cost for renewing a domain
   * name. This cost is also used to compute costs for transfers, since each transfer includes a
   * renewal to ensure transfers have a cost.
   */
  @Column(nullable = false)
  @Type(BillingCostTransitionUserType.class)
  @JsonDeserialize(using = TimedTransitionPropertyMoneyDeserializer.class)
  TimedTransitionProperty<Money> renewBillingCostTransitions =
      TimedTransitionProperty.withInitialValue(DEFAULT_RENEW_BILLING_COST);

  /** A property that tracks the EAP fee schedule (if any) for the TLD. */
  @Column(nullable = false)
  @Type(BillingCostTransitionUserType.class)
  @JsonDeserialize(using = TimedTransitionPropertyMoneyDeserializer.class)
  TimedTransitionProperty<Money> eapFeeSchedule =
      TimedTransitionProperty.withInitialValue(DEFAULT_EAP_BILLING_COST);

  /** Marksdb LORDN service username (password is stored in Keyring) */
  String lordnUsername;

  /** The end of the claims period (at or after this time, claims no longer applies). */
  @Column(nullable = false)
  DateTime claimsPeriodEnd = END_OF_TIME;

  /** An allowlist of hosts allowed to be used on domains on this TLD (ignored if empty). */
  @Nullable
  @JsonSerialize(using = SortedSetSerializer.class)
  Set<String> allowedFullyQualifiedHostNames;

  /**
   * Indicates when the TLD is being modified using locally modified files to override the source
   * control procedures. This field is ignored in Tld YAML files.
   */
  @JsonIgnore
  @Column(nullable = false)
  boolean breakglassMode = false;

  /**
   * References to allocation tokens that can be used on the TLD if no other token is passed in on a
   * domain create.
   *
   * <p>Ordering is important for this field as it will determine which token is used if multiple
   * tokens in the list are valid for a specific registration. It is crucial that modifications to
   * this field only modify the entire list contents. Modifications to a single token in the list
   * (ex: add a token to the list or remove a token from the list) should not be allowed without
   * resetting the entire list contents.
   */
  @Type(AllocationTokenVkeyListUserType.class)
  @JsonSerialize(using = TokenVKeyListSerializer.class)
  @JsonDeserialize(using = TokenVKeyListDeserializer.class)
  List<VKey<AllocationToken>> defaultPromoTokens;

  /** A set of allowed {@link IdnTableEnum}s for this TLD, or empty if we should use the default. */
  // @Type(IdnTableEnumSetUserType.class)
  @Enumerated(EnumType.STRING)
  @JsonSerialize(using = SortedEnumSetSerializer.class)
  Set<IdnTableEnum> idnTables;

  /**
   * The start time of this TLD's enrollment in the BSA program, if applicable.
   *
   * <p>This property is excluded from source-based configuration and is managed directly in the
   * database.
   */
  // TODO(b/309175410): implement setup and cleanup procedure for joining or leaving BSA, and see
  // if it can be integrated with the ConfigTldCommand.
  @JsonIgnore @Nullable DateTime bsaEnrollStartTime;

  public String getTldStr() {
    return tldStr;
  }

  public String getRoidSuffix() {
    return roidSuffix;
  }

  /** Retrieve the actual domain name representing the TLD for which this registry operates. */
  @JsonIgnore
  public InternetDomainName getTld() {
    return InternetDomainName.from(tldStr);
  }

  /** Retrieve the TLD type (real or test). */
  public TldType getTldType() {
    return tldType;
  }

  /** Returns the time when this TLD was enrolled in the Brand Safety Alliance (BSA) program. */
  @JsonIgnore
  public Optional<DateTime> getBsaEnrollStartTime() {
    return Optional.ofNullable(this.bsaEnrollStartTime);
  }

  /** Retrieve whether invoicing is enabled. */
  public boolean isInvoicingEnabled() {
    return invoicingEnabled;
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

  @Nullable
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

  public Optional<String> getPremiumListName() {
    return Optional.ofNullable(premiumListName);
  }

  public CurrencyUnit getCurrency() {
    return currency;
  }

  /**
   * Use {@code PricingEngineProxy.getDomainCreateCost} instead of this to find the cost for a
   * domain create.
   */
  public Money getCreateBillingCost(DateTime now) {
    return createBillingCostTransitions.getValueAtTime(now);
  }

  public ImmutableSortedMap<DateTime, Money> getCreateBillingCostTransitions() {
    return createBillingCostTransitions.toValueMap();
  }

  /**
   * Returns the add-on cost of a domain restore (the flat tld-wide fee charged in addition to one
   * year of renewal for that name).
   */
  public Money getRestoreBillingCost() {
    return restoreBillingCost;
  }

  /**
   * Use {@code PricingEngineProxy.getDomainRenewCost} instead of this to find the cost for a domain
   * renewal, and all derived costs (i.e. autorenews, transfers, and the per-domain part of a
   * restore cost).
   */
  public Money getStandardRenewCost(DateTime now) {
    return renewBillingCostTransitions.getValueAtTime(now);
  }

  /** Returns the cost of a server status change (i.e. lock). */
  public Money getServerStatusChangeBillingCost() {
    return serverStatusChangeBillingCost;
  }

  /** Returns the cost of a registry lock/unlock. */
  public Money getRegistryLockOrUnlockBillingCost() {
    return registryLockOrUnlockBillingCost;
  }

  public ImmutableSortedMap<DateTime, TldState> getTldStateTransitions() {
    return tldStateTransitions.toValueMap();
  }

  public ImmutableSortedMap<DateTime, Money> getRenewBillingCostTransitions() {
    return renewBillingCostTransitions.toValueMap();
  }

  /** Returns the EAP fee for the tld at the given time. */
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
  @JsonProperty("eapFeeSchedule")
  public ImmutableSortedMap<DateTime, Money> getEapFeeScheduleAsMap() {
    return eapFeeSchedule.toValueMap();
  }

  public String getLordnUsername() {
    return lordnUsername;
  }

  public DateTime getClaimsPeriodEnd() {
    return claimsPeriodEnd;
  }

  public String getPricingEngineClassName() {
    return pricingEngineClassName;
  }

  public ImmutableSet<String> getDnsWriters() {
    return ImmutableSet.copyOf(dnsWriters);
  }

  /** Returns the number of simultaneous DNS publish operations we allow at once. */
  public int getNumDnsPublishLocks() {
    return numDnsPublishLocks;
  }

  /** Returns the time to live for A and AAAA records. */
  public Optional<Duration> getDnsAPlusAaaaTtl() {
    return Optional.ofNullable(dnsAPlusAaaaTtl);
  }

  /** Returns the time to live for NS records. */
  public Optional<Duration> getDnsNsTtl() {
    return Optional.ofNullable(dnsNsTtl);
  }

  /** Returns the time to live for DS records. */
  public Optional<Duration> getDnsDsTtl() {
    return Optional.ofNullable(dnsDsTtl);
  }

  /** Retrieve the TLD unicode representation. */
  public String getTldUnicode() {
    return tldUnicode;
  }

  public ImmutableSet<String> getAllowedFullyQualifiedHostNames() {
    return nullToEmptyImmutableCopy(allowedFullyQualifiedHostNames);
  }

  public ImmutableList<VKey<AllocationToken>> getDefaultPromoTokens() {
    return nullToEmptyImmutableCopy(defaultPromoTokens);
  }

  public ImmutableSet<IdnTableEnum> getIdnTables() {
    return nullToEmptyImmutableCopy(idnTables);
  }

  public boolean getBreakglassMode() {
    return breakglassMode;
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link Tld} objects, since they are immutable. */
  public static class Builder extends Buildable.Builder<Tld> {
    public Builder() {}

    private Builder(Tld instance) {
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
                  tldStatesMap.values().stream()
                      .filter(state -> !TldState.QUIET_PERIOD.equals(state))
                      .collect(Collectors.toList())),
          "The TLD states are chronologically out of order");
      getInstance().tldStateTransitions = TimedTransitionProperty.fromValueMap(tldStatesMap);
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

    public Builder setDnsAPlusAaaaTtl(Duration dnsAPlusAaaaTtl) {
      getInstance().dnsAPlusAaaaTtl = dnsAPlusAaaaTtl;
      return this;
    }

    public Builder setDnsNsTtl(Duration dnsNsTtl) {
      getInstance().dnsNsTtl = dnsNsTtl;
      return this;
    }

    public Builder setDnsDsTtl(Duration dnsDsTtl) {
      getInstance().dnsDsTtl = dnsDsTtl;
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

    public Builder setCreateBillingCostTransitions(
        ImmutableSortedMap<DateTime, Money> createCostsMap) {
      checkArgumentNotNull(createCostsMap, "Create billing costs map cannot be null");
      checkArgument(
          createCostsMap.values().stream().allMatch(Money::isPositiveOrZero),
          "Create billing cost cannot be negative");
      getInstance().createBillingCostTransitions =
          TimedTransitionProperty.fromValueMap(createCostsMap);
      return this;
    }

    public Builder setReservedListsByName(Set<String> reservedListNames) {
      // TODO(b/309175133): forbid if enrolled with BSA
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
      // TODO(b/309175133): forbid if enrolled with BSA
      checkArgumentNotNull(reservedLists, "reservedLists must not be null");
      ImmutableSet.Builder<String> nameBuilder = new ImmutableSet.Builder<>();
      for (ReservedList reservedList : reservedLists) {
        nameBuilder.add(reservedList.getName());
      }
      getInstance().reservedListNames = nameBuilder.build();
      return this;
    }

    public Builder setPremiumList(@Nullable PremiumList premiumList) {
      getInstance().premiumListName = premiumList == null ? null : premiumList.getName();
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
          TimedTransitionProperty.fromValueMap(renewCostsMap);
      return this;
    }

    /** Sets the EAP fee schedule for the TLD. */
    public Builder setEapFeeSchedule(ImmutableSortedMap<DateTime, Money> eapFeeSchedule) {
      checkArgumentNotNull(eapFeeSchedule, "EAP schedule map cannot be null");
      checkArgument(
          eapFeeSchedule.values().stream().allMatch(Money::isPositiveOrZero),
          "EAP fee cannot be negative");
      getInstance().eapFeeSchedule = TimedTransitionProperty.fromValueMap(eapFeeSchedule);
      return this;
    }

    public static final Pattern ROID_SUFFIX_PATTERN = Pattern.compile("^[A-Z\\d_]{1,8}$");

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

    public Builder setRegistryLockOrUnlockBillingCost(Money amount) {
      checkArgument(amount.isPositiveOrZero(), "Registry lock/unlock cost cannot be negative");
      getInstance().registryLockOrUnlockBillingCost = amount;
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

    public Builder setAllowedFullyQualifiedHostNames(
        ImmutableSet<String> allowedFullyQualifiedHostNames) {
      getInstance().allowedFullyQualifiedHostNames = allowedFullyQualifiedHostNames;
      return this;
    }

    public Builder setDefaultPromoTokens(ImmutableList<VKey<AllocationToken>> promoTokens) {
      tm().transact(
              () -> {
                for (VKey<AllocationToken> tokenKey : promoTokens) {
                  AllocationToken token = tm().loadByKey(tokenKey);
                  checkArgument(
                      token.getTokenType().equals(TokenType.DEFAULT_PROMO),
                      String.format(
                          "Token %s has an invalid token type of %s. DefaultPromoTokens must be of"
                              + " the type DEFAULT_PROMO",
                          token.getToken(), token.getTokenType()));
                  checkArgument(
                      token.getAllowedTlds().contains(getInstance().tldStr),
                      String.format(
                          "The token %s is not valid for this TLD. The valid TLDs for it are %s",
                          token.getToken(), token.getAllowedTlds()));
                }
                getInstance().defaultPromoTokens = promoTokens;
              });
      return this;
    }

    public Builder setIdnTables(ImmutableSet<IdnTableEnum> idnTables) {
      // TODO(b/309175133): forbid if enrolled with BSA.
      getInstance().idnTables = idnTables;
      return this;
    }

    public Builder setBreakglassMode(boolean breakglassMode) {
      getInstance().breakglassMode = breakglassMode;
      return this;
    }

    public Builder setBsaEnrollStartTime(Optional<DateTime> enrollTime) {
      // TODO(b/309175133): forbid if enrolled with BSA
      getInstance().bsaEnrollStartTime = enrollTime.orElse(null);
      return this;
    }

    @Override
    public Tld build() {
      final Tld instance = getInstance();
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
      // here to catch cases where we loaded an invalid TimedTransitionProperty from the database
      // and cloned it into a new builder, to block re-building a Tld in an invalid state.
      instance.tldStateTransitions.checkValidity();
      instance.createBillingCostTransitions.checkValidity();
      instance.renewBillingCostTransitions.checkValidity();
      instance.eapFeeSchedule.checkValidity();
      // All costs must be in the expected currency.
      checkArgumentNotNull(instance.getCurrency(), "Currency must be set");
      checkArgument(
          instance.getRestoreBillingCost().getCurrencyUnit().equals(instance.currency),
          "Restore cost must be in the TLD's currency");
      checkArgument(
          instance.getServerStatusChangeBillingCost().getCurrencyUnit().equals(instance.currency),
          "Server status change cost must be in the TLD's currency");
      checkArgument(
          instance.getRegistryLockOrUnlockBillingCost().getCurrencyUnit().equals(instance.currency),
          "Registry lock/unlock cost must be in the TLD's currency");
      Predicate<Money> currencyCheck =
          (Money money) -> money.getCurrencyUnit().equals(instance.currency);
      checkArgument(
          instance.getRenewBillingCostTransitions().values().stream().allMatch(currencyCheck),
          "Renew cost must be in the TLD's currency");
      checkArgument(
          instance.getCreateBillingCostTransitions().values().stream().allMatch(currencyCheck),
          "Create cost must be in the TLD's currency");
      checkArgument(
          instance.eapFeeSchedule.toValueMap().values().stream().allMatch(currencyCheck),
          "All EAP fees must be in the TLD's currency");
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
      instance.tldStr = tldName;
      instance.tldUnicode = Idn.toUnicode(tldName);
      return super.build();
    }
  }

  /** Exception to throw when no Tld entity is found for given TLD string(s). */
  public static class TldNotFoundException extends RuntimeException {

    TldNotFoundException(ImmutableSet<String> tlds) {
      super("No TLD object(s) found for " + Joiner.on(", ").join(tlds));
    }

    TldNotFoundException(String tld) {
      this(ImmutableSet.of(tld));
    }
  }
}
