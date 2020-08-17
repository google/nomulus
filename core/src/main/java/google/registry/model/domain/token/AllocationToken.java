// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.domain.token.AllocationToken.TokenStatus.CANCELLED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.ENDED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.NOT_STARTED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.VALID;
import static google.registry.util.CollectionUtils.forceEmptyToNull;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Range;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.annotation.Mapify;
import com.googlecode.objectify.annotation.OnLoad;
import google.registry.flows.EppException;
import google.registry.flows.domain.DomainFlowUtils;
import google.registry.model.BackupGroupRoot;
import google.registry.model.Buildable;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.model.common.TimedTransitionProperty.TimeMapper;
import google.registry.model.common.TimedTransitionProperty.TimedTransition;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.persistence.WithStringVKey;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/** An entity representing an allocation token. */
@ReportedOn
@Entity
@WithStringVKey
public class AllocationToken extends BackupGroupRoot implements Buildable {

  // Promotions should only move forward, and ENDED / CANCELLED are terminal states.
  private static final ImmutableMultimap<TokenStatus, TokenStatus> VALID_TOKEN_STATUS_TRANSITIONS =
      ImmutableMultimap.<TokenStatus, TokenStatus>builder()
          .putAll(NOT_STARTED, VALID, CANCELLED)
          .putAll(VALID, ENDED, CANCELLED)
          .build();

  /** Single-use tokens are invalid after use. Infinite-use tokens, predictably, are not. */
  public enum TokenType {
    SINGLE_USE,
    UNLIMITED_USE
  }

  /** The status of this token with regards to any potential promotion. */
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
  @Nullable @Index Key<HistoryEntry> redemptionHistoryEntry;

  /** The fully-qualified domain name that this token is limited to, if any. */
  @Nullable @Index String domainName;

  /** When this token was created. */
  CreateAutoTimestamp creationTime = CreateAutoTimestamp.create(null);

  /** Allowed registrar client IDs for this token, or null if all registrars are allowed. */
  @Nullable Set<String> allowedClientIds;

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
  TokenType tokenType;

  // TODO: Remove onLoad once all allocation tokens are migrated to have a discountYears of 1.
  @OnLoad
  void onLoad() {
    if (discountYears == 0) {
      discountYears = 1;
    }
  }

  /**
   * Promotional token validity periods.
   *
   * <p>If the token is promotional, the status will be VALID at the start of the promotion and
   * ENDED at the end. If manually cancelled, we will add a CANCELLED status.
   */
  @Mapify(TimeMapper.class)
  TimedTransitionProperty<TokenStatus, TokenStatusTransition> tokenStatusTransitions =
      TimedTransitionProperty.forMapify(NOT_STARTED, TokenStatusTransition.class);

  /**
   * A transition to a given token status at a specific time, for use in a TimedTransitionProperty.
   *
   * <p>Public because App Engine's security manager requires this for instantiation via reflection.
   */
  @Embed
  public static class TokenStatusTransition extends TimedTransition<TokenStatus> {
    private TokenStatus tokenStatus;

    @Override
    public TokenStatus getValue() {
      return tokenStatus;
    }

    @Override
    protected void setValue(TokenStatus tokenStatus) {
      this.tokenStatus = tokenStatus;
    }
  }

  public String getToken() {
    return token;
  }

  public Optional<Key<HistoryEntry>> getRedemptionHistoryEntry() {
    return Optional.ofNullable(redemptionHistoryEntry);
  }

  public boolean isRedeemed() {
    return redemptionHistoryEntry != null;
  }

  public Optional<String> getDomainName() {
    return Optional.ofNullable(domainName);
  }

  public Optional<DateTime> getCreationTime() {
    return Optional.ofNullable(creationTime.getTimestamp());
  }

  public ImmutableSet<String> getAllowedClientIds() {
    return nullToEmptyImmutableCopy(allowedClientIds);
  }

  public ImmutableSet<String> getAllowedTlds() {
    return nullToEmptyImmutableCopy(allowedTlds);
  }

  public double getDiscountFraction() {
    return discountFraction;
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

  public TimedTransitionProperty<TokenStatus, TokenStatusTransition> getTokenStatusTransitions() {
    return tokenStatusTransitions;
  }

  public VKey<AllocationToken> createVKey() {
    return VKey.create(AllocationToken.class, getToken(), Key.create(this));
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
          getInstance().domainName == null || TokenType.SINGLE_USE.equals(getInstance().tokenType),
          "Domain name can only be specified for SINGLE_USE tokens");
      checkArgument(
          getInstance().redemptionHistoryEntry == null
              || TokenType.SINGLE_USE.equals(getInstance().tokenType),
          "Redemption history entry can only be specified for SINGLE_USE tokens");
      checkArgument(
          getInstance().discountFraction > 0 || !getInstance().discountPremiums,
          "Discount premiums can only be specified along with a discount fraction");
      checkArgument(
          getInstance().discountFraction > 0 || getInstance().discountYears == 1,
          "Discount years can only be specified along with a discount fraction");
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

    public Builder setRedemptionHistoryEntry(Key<HistoryEntry> redemptionHistoryEntry) {
      getInstance().redemptionHistoryEntry =
          checkArgumentNotNull(redemptionHistoryEntry, "Redemption history entry must not be null");
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

    public Builder setAllowedClientIds(Set<String> allowedClientIds) {
      getInstance().allowedClientIds = forceEmptyToNull(allowedClientIds);
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
              TokenStatusTransition.class,
              VALID_TOKEN_STATUS_TRANSITIONS,
              "tokenStatusTransitions",
              NOT_STARTED,
              "tokenStatusTransitions must start with NOT_STARTED");
      return this;
    }
  }
}
