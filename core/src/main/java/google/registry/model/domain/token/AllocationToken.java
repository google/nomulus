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

package google.registry.model.domain.token;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static google.registry.config.RegistryConfig.getSingletonCacheRefreshDuration;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.CANCELLED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.ENDED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.NOT_STARTED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.VALID;
import static google.registry.model.domain.token.AllocationToken.TokenType.REGISTER_BSA;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.CollectionUtils.forceEmptyToNull;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Range;
import google.registry.flows.EppException;
import google.registry.flows.domain.DomainFlowUtils;
import google.registry.model.Buildable;
import google.registry.model.CacheUtils;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.UpdateAutoTimestampEntity;
import google.registry.model.billing.BillingBase.RenewalPriceBehavior;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.persistence.VKey;
import google.registry.persistence.WithVKey;
import google.registry.persistence.converter.AllocationTokenStatusTransitionUserType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.hibernate.annotations.Type;
import org.joda.money.Money;
import org.joda.time.DateTime;

/** An entity representing an allocation token. */
@Entity
@WithVKey(String.class)
@Table(
    indexes = {
      @Index(columnList = "token", name = "allocation_token_token_idx", unique = true),
      @Index(columnList = "domainName", name = "allocation_token_domain_name_idx"),
      @Index(columnList = "tokenType"),
      @Index(columnList = "redemption_domain_repo_id")
    })
public class AllocationToken extends UpdateAutoTimestampEntity implements Buildable {

  private static final long serialVersionUID = -3954475393220876903L;
  private static final String REMOVE_BULK_PRICING = "__REMOVE_BULK_PRICING__";

  private static final ImmutableMap<String, TokenBehavior> STATIC_TOKEN_BEHAVIORS =
      ImmutableMap.of(REMOVE_BULK_PRICING, TokenBehavior.REMOVE_BULK_PRICING);

  // Promotions should only move forward, and ENDED / CANCELLED are terminal states.
  private static final ImmutableMultimap<TokenStatus, TokenStatus> VALID_TOKEN_STATUS_TRANSITIONS =
      ImmutableMultimap.<TokenStatus, TokenStatus>builder()
          .putAll(NOT_STARTED, VALID, CANCELLED)
          .putAll(VALID, ENDED, CANCELLED)
          .build();

  private static final ImmutableMap<String, AllocationToken> BEHAVIORAL_TOKENS =
      ImmutableMap.of(
          REMOVE_BULK_PRICING,
          new AllocationToken.Builder()
              .setTokenType(TokenType.UNLIMITED_USE)
              .setToken(REMOVE_BULK_PRICING)
              .build());

  public static Optional<AllocationToken> maybeGetStaticTokenInstance(String name) {
    return Optional.ofNullable(BEHAVIORAL_TOKENS.get(name));
  }

  /** Any special behavior that should be used when registering domains using this token. */
  public enum RegistrationBehavior {
    /** No special behavior */
    DEFAULT,
    /**
     * Bypasses the TLD state check, e.g. allowing registration during QUIET_PERIOD.
     *
     * <p>NB: while this means that, for instance, one can register non-trademarked domains in the
     * sunrise period, any trademarked-domain registrations in the sunrise period must still include
     * the proper signed marks. In other words, this only bypasses the TLD state check.
     */
    BYPASS_TLD_STATE,
    /** Bypasses most checks and creates the domain as an anchor tenant, with all that implies. */
    ANCHOR_TENANT,
    /**
     * Bypasses the premium list to use the standard creation price. Does not affect the renewal
     * price.
     *
     * <p>This cannot be specified along with a discount fraction/price, and any renewals (automatic
     * or otherwise) will use the premium price for the domain if one exists.
     *
     * <p>Tokens with this behavior must be tied to a single particular domain.
     */
    NONPREMIUM_CREATE
  }

  /** Type of the token that indicates how and where it should be used. */
  public enum TokenType {
    /** Token used for bulk pricing */
    BULK_PRICING(/* isOneTimeUse= */ false),
    /** Token saved on a TLD to use if no other token is passed from the client */
    DEFAULT_PROMO(/* isOneTimeUse= */ false),
    /** This is the old name for what is now BULK_PRICING. */
    // TODO(b/261763205): Remove this type once all tokens of this type have been scrubbed from the
    // database
    @Deprecated
    PACKAGE(/* isOneTimeUse= */ false),
    /** Invalid after use */
    SINGLE_USE(/* isOneTimeUse= */ true),
    /** Do not expire after use */
    UNLIMITED_USE(/* isOneTimeUse= */ false),
    /**
     * Allows bypassing the BSA check during domain creation, otherwise has the same semantics as
     * {@link #SINGLE_USE}.
     *
     * <p>This token applies to a single domain only. If the domain is not blocked by BSA at the
     * redemption time this token is processed like {@code SINGLE_USE}, as mentioned above.
     */
    REGISTER_BSA(/* isOneTimeUse= */ true);

    private final boolean isOneTimeUse;

    private TokenType(boolean isOneTimeUse) {
      this.isOneTimeUse = isOneTimeUse;
    }

    /** Returns true if token should be invalidated after use. */
    public boolean isOneTimeUse() {
      return this.isOneTimeUse;
    }
  }

  /**
   * System behaves differently based on a token it gets inside a command. This enumerates different
   * types of behaviors we support.
   */
  public enum TokenBehavior {
    /** No special behavior */
    DEFAULT,
    /**
     * REMOVE_BULK_PRICING triggers domain removal from a bulk pricing package, bypasses DEFAULT
     * token validations.
     */
    REMOVE_BULK_PRICING
  }

  /** The status of this token with regard to any potential promotion. */
  public enum TokenStatus {
    /** Default status for a token. Either a promotion doesn't exist or it hasn't started. */
    NOT_STARTED,
    /** A promotion is currently running. */
    VALID,
    /** The promotion has ended. */
    ENDED,
    /** The promotion was manually invalidated. */
    CANCELLED
  }

  /** The allocation token string. */
  @Id String token;

  /** The key of the history entry for which the token was used. Null if not yet used. */
  @Nullable
  @AttributeOverrides({
    @AttributeOverride(name = "repoId", column = @Column(name = "redemption_domain_repo_id")),
    @AttributeOverride(name = "revisionId", column = @Column(name = "redemption_domain_history_id"))
  })
  HistoryEntryId redemptionHistoryId;

  /** The fully-qualified domain name that this token is limited to, if any. */
  @Nullable String domainName;

  /** When this token was created. */
  CreateAutoTimestamp creationTime = CreateAutoTimestamp.create(null);

  /** Allowed registrar client IDs for this token, or null if all registrars are allowed. */
  @Column(name = "allowedRegistrarIds")
  @Nullable
  Set<String> allowedClientIds;

  /** Allowed TLDs for this token, or null if all TLDs are allowed. */
  @Nullable Set<String> allowedTlds;

  /**
   * For promotions, a discount off the base price for the first year between 0.0 and 1.0.
   *
   * <p>e.g. a value of 0.15 will mean a 15% discount off the base price for the first year.
   */
  double discountFraction;

  /** Whether the discount fraction (if any) also applies to premium names. Defaults to false. */
  boolean discountPremiums;

  /** Up to how many years of initial creation receive the discount (if any). Defaults to 1. */
  int discountYears = 1;

  /** The type of the token, either single-use or unlimited-use. */
  @Enumerated(EnumType.STRING)
  TokenType tokenType;

  @Enumerated(EnumType.STRING)
  @Column(name = "renewalPriceBehavior", nullable = false)
  RenewalPriceBehavior renewalPriceBehavior = RenewalPriceBehavior.DEFAULT;

  /** The price used for renewals iff the renewalPriceBehavior is SPECIFIED. */
  @Nullable
  @AttributeOverride(
      name = "amount",
      // Override Hibernate default (numeric(38,2)) to match real schema definition (numeric(19,2)).
      column = @Column(name = "renewalPriceAmount", precision = 19, scale = 2))
  @AttributeOverride(name = "currency", column = @Column(name = "renewalPriceCurrency"))
  Money renewalPrice;

  /**
   * A discount that allows the setting of promotional prices. This field is different from {@code
   * discountFraction} because the price set here is treated as the domain price, versus {@code
   * discountFraction} that applies a fraction discount to the domain base price.
   *
   * <p>Prefer this method of discount when attempting to set a promotional price across TLDs with
   * different base prices.
   */
  @Nullable
  @AttributeOverride(
      name = "amount",
      // Override Hibernate default (numeric(38,2)) to match real schema definition (numeric(19,2)).
      column = @Column(name = "discountPriceAmount", precision = 19, scale = 2))
  @AttributeOverride(name = "currency", column = @Column(name = "discountPriceCurrency"))
  Money discountPrice;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  RegistrationBehavior registrationBehavior = RegistrationBehavior.DEFAULT;

  /**
   * Promotional token validity periods.
   *
   * <p>If the token is promotional, the status will be VALID at the start of the promotion and
   * ENDED at the end. If manually cancelled, we will add a CANCELLED status.
   */
  @Type(AllocationTokenStatusTransitionUserType.class)
  TimedTransitionProperty<TokenStatus> tokenStatusTransitions =
      TimedTransitionProperty.withInitialValue(NOT_STARTED);

  /** Allowed EPP actions for this token, or null if all actions are allowed. */
  @Nullable
  @Enumerated(EnumType.STRING)
  Set<CommandName> allowedEppActions;

  public String getToken() {
    return token;
  }

  public Optional<HistoryEntryId> getRedemptionHistoryId() {
    return Optional.ofNullable(redemptionHistoryId);
  }

  public boolean isRedeemed() {
    return redemptionHistoryId != null;
  }

  public Optional<String> getDomainName() {
    return Optional.ofNullable(domainName);
  }

  public Optional<DateTime> getCreationTime() {
    return Optional.ofNullable(creationTime.getTimestamp());
  }

  public ImmutableSet<String> getAllowedRegistrarIds() {
    return nullToEmptyImmutableCopy(allowedClientIds);
  }

  public ImmutableSet<String> getAllowedTlds() {
    return nullToEmptyImmutableCopy(allowedTlds);
  }

  public double getDiscountFraction() {
    return discountFraction;
  }

  public Optional<Money> getDiscountPrice() {
    return Optional.ofNullable(discountPrice);
  }

  public boolean shouldDiscountPremiums() {
    return discountPremiums;
  }

  public int getDiscountYears() {
    // Allocation tokens created prior to the addition of the discountYears field will have a value
    // of 0 for it, but it should be the default value of 1 to retain the previous behavior.
    return Math.max(1, discountYears);
  }

  public TokenType getTokenType() {
    return tokenType;
  }

  public TimedTransitionProperty<TokenStatus> getTokenStatusTransitions() {
    return tokenStatusTransitions;
  }

  public ImmutableSet<CommandName> getAllowedEppActions() {
    return nullToEmptyImmutableCopy(allowedEppActions);
  }

  public RenewalPriceBehavior getRenewalPriceBehavior() {
    return renewalPriceBehavior;
  }

  public Optional<Money> getRenewalPrice() {
    return Optional.ofNullable(renewalPrice);
  }

  public RegistrationBehavior getRegistrationBehavior() {
    return registrationBehavior;
  }

  public TokenBehavior getTokenBehavior() {
    return STATIC_TOKEN_BEHAVIORS.getOrDefault(token, TokenBehavior.DEFAULT);
  }

  public static Optional<AllocationToken> get(VKey<AllocationToken> key) {
    return ALLOCATION_TOKENS_CACHE.get(key);
  }

  public static Map<VKey<AllocationToken>, Optional<AllocationToken>> getAll(
      ImmutableList<VKey<AllocationToken>> keys) {
    return ALLOCATION_TOKENS_CACHE.getAll(keys);
  }

  /** A cache that loads the {@link AllocationToken} object for a given AllocationToken VKey. */
  private static final LoadingCache<VKey<AllocationToken>, Optional<AllocationToken>>
      ALLOCATION_TOKENS_CACHE =
          CacheUtils.newCacheBuilder(getSingletonCacheRefreshDuration())
              .build(
                  new CacheLoader<>() {
                    @Override
                    public Optional<AllocationToken> load(VKey<AllocationToken> key) {
                      return tm().reTransact(() -> tm().loadByKeyIfPresent(key));
                    }

                    @Override
                    public Map<? extends VKey<AllocationToken>, ? extends Optional<AllocationToken>>
                        loadAll(Set<? extends VKey<AllocationToken>> keys) {
                      return tm().reTransact(
                              () ->
                                  keys.stream()
                                      .collect(
                                          toImmutableMap(
                                              key -> key, key -> tm().loadByKeyIfPresent(key))));
                    }
                  });

  @Override
  public VKey<AllocationToken> createVKey() {
    if (!AllocationToken.TokenBehavior.DEFAULT.equals(getTokenBehavior())) {
      throw new IllegalArgumentException(
          String.format("%s tokens are not stored in the database", getTokenBehavior()));
    }
    return VKey.create(AllocationToken.class, getToken());
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link AllocationToken} objects, since they are immutable. */
  public static class Builder extends Buildable.Builder<AllocationToken> {

    public Builder() {}

    private Builder(AllocationToken instance) {
      super(instance);
    }

    @Override
    public AllocationToken build() {
      checkArgumentNotNull(getInstance().tokenType, "Token type must be specified");
      checkArgument(!Strings.isNullOrEmpty(getInstance().token), "Token must not be null or empty");
      checkArgument(
          getInstance().domainName == null || getInstance().tokenType.isOneTimeUse(),
          "Domain name can only be specified for SINGLE_USE or REGISTER_BSA tokens");
      checkArgument(
          getInstance().redemptionHistoryId == null || getInstance().tokenType.isOneTimeUse(),
          "Redemption history entry can only be specified for SINGLE_USE or REGISTER_BSA tokens");
      checkArgument(
          getInstance().discountFraction > 0 || !getInstance().discountPremiums,
          "Discount premiums can only be specified along with a discount fraction");
      checkArgument(
          getInstance().discountFraction > 0
              || getInstance().discountPrice != null
              || getInstance().discountYears == 1,
          "Discount years can only be specified along with a discount fraction/price");
      if (getInstance().getTokenType().equals(REGISTER_BSA)) {
        checkArgumentNotNull(
            getInstance().domainName, "REGISTER_BSA tokens must be tied to a domain");
      }
      if (getInstance().registrationBehavior.equals(RegistrationBehavior.ANCHOR_TENANT)) {
        checkArgumentNotNull(
            getInstance().domainName, "ANCHOR_TENANT tokens must be tied to a domain");
      }
      if (getInstance().registrationBehavior.equals(RegistrationBehavior.NONPREMIUM_CREATE)) {
        checkArgument(
            getInstance().discountFraction == 0.0 && getInstance().discountPrice == null,
            "NONPREMIUM_CREATE tokens cannot apply a discount");
        checkArgumentNotNull(
            getInstance().domainName, "NONPREMIUM_CREATE tokens must be tied to a domain");
        checkArgument(
            getInstance().allowedEppActions == null
                || getInstance().allowedEppActions.contains(CommandName.CREATE),
            "NONPREMIUM_CREATE tokens must allow for CREATE actions");
      }

      checkArgument(
          getInstance().renewalPriceBehavior.equals(RenewalPriceBehavior.SPECIFIED)
              == (getInstance().renewalPrice != null),
          "renewalPrice must be specified iff renewalPriceBehavior is SPECIFIED");

      if (getInstance().tokenType.equals(TokenType.BULK_PRICING)) {
        checkArgument(
            getInstance().discountFraction == 1.0,
            "BULK_PRICING tokens must have a discountFraction of 1.0");
        checkArgument(
            !getInstance().shouldDiscountPremiums(),
            "BULK_PRICING tokens cannot discount premium names");
        checkArgument(
            getInstance().renewalPriceBehavior.equals(RenewalPriceBehavior.SPECIFIED),
            "BULK_PRICING tokens must have renewalPriceBehavior set to SPECIFIED");
        checkArgument(
            getInstance().renewalPrice.getAmount().intValue() == 0,
            "BULK_PRICING tokens must have a renewal price of 0");
        checkArgument(
            ImmutableSet.of(CommandName.CREATE).equals(getInstance().allowedEppActions),
            "BULK_PRICING tokens may only be valid for CREATE actions");
        checkArgument(
            getInstance().allowedClientIds != null && getInstance().allowedClientIds.size() == 1,
            "BULK_PRICING tokens must have exactly one allowed client registrar");
      }

      if (getInstance().discountFraction != 0.0) {
        checkArgument(
            getInstance().discountPrice == null,
            "discountFraction and discountPrice can't be set together");
      }

      if (getInstance().domainName != null) {
        try {
          DomainFlowUtils.validateDomainName(getInstance().domainName);
        } catch (EppException e) {
          throw new IllegalArgumentException("Invalid domain name: " + getInstance().domainName, e);
        }
      }
      return super.build();
    }

    public Builder setToken(String token) {
      checkState(getInstance().token == null, "Token can only be set once");
      checkArgumentNotNull(token, "Token must not be null");
      checkArgument(!token.isEmpty(), "Token must not be blank");
      getInstance().token = token;
      return this;
    }

    public Builder setRedemptionHistoryId(HistoryEntryId redemptionHistoryId) {
      checkArgumentNotNull(redemptionHistoryId, "Redemption history entry ID must not be null");
      getInstance().redemptionHistoryId = redemptionHistoryId;
      return this;
    }

    public Builder setDomainName(@Nullable String domainName) {
      getInstance().domainName = domainName;
      return this;
    }

    @VisibleForTesting
    public Builder setCreationTimeForTest(DateTime creationTime) {
      checkState(
          getInstance().creationTime.getTimestamp() == null, "Creation time can only be set once");
      getInstance().creationTime = CreateAutoTimestamp.create(creationTime);
      return this;
    }

    public Builder setAllowedRegistrarIds(Set<String> allowedRegistrarIds) {
      getInstance().allowedClientIds = forceEmptyToNull(allowedRegistrarIds);
      return this;
    }

    public Builder setAllowedTlds(Set<String> allowedTlds) {
      getInstance().allowedTlds = forceEmptyToNull(allowedTlds);
      return this;
    }

    public Builder setDiscountFraction(double discountFraction) {
      checkArgument(
          Range.closed(0.0d, 1.0d).contains(discountFraction),
          "Discount fraction must be between 0 and 1 inclusive");
      getInstance().discountFraction = discountFraction;
      return this;
    }

    public Builder setDiscountPremiums(boolean discountPremiums) {
      getInstance().discountPremiums = discountPremiums;
      return this;
    }

    public Builder setDiscountYears(int discountYears) {
      checkArgument(
          Range.closed(1, 10).contains(discountYears),
          "Discount years must be between 1 and 10 inclusive");
      getInstance().discountYears = discountYears;
      return this;
    }

    public Builder setTokenType(TokenType tokenType) {
      checkState(getInstance().tokenType == null, "Token type can only be set once");
      getInstance().tokenType = tokenType;
      return this;
    }

    public Builder setTokenStatusTransitions(
        ImmutableSortedMap<DateTime, TokenStatus> transitions) {
      getInstance().tokenStatusTransitions =
          TimedTransitionProperty.make(
              transitions,
              VALID_TOKEN_STATUS_TRANSITIONS,
              "tokenStatusTransitions",
              NOT_STARTED,
              "tokenStatusTransitions must start with NOT_STARTED");
      return this;
    }

    public Builder setAllowedEppActions(Set<CommandName> allowedEppActions) {
      getInstance().allowedEppActions = forceEmptyToNull(allowedEppActions);
      return this;
    }

    public Builder setRenewalPriceBehavior(RenewalPriceBehavior renewalPriceBehavior) {
      getInstance().renewalPriceBehavior = renewalPriceBehavior;
      return this;
    }

    public Builder setRenewalPrice(Money renewalPrice) {
      getInstance().renewalPrice = renewalPrice;
      return this;
    }

    public Builder setRegistrationBehavior(RegistrationBehavior registrationBehavior) {
      getInstance().registrationBehavior = registrationBehavior;
      return this;
    }

    public Builder setDiscountPrice(@Nullable Money discountPrice) {
      getInstance().discountPrice = discountPrice;
      return this;
    }
  }
}
