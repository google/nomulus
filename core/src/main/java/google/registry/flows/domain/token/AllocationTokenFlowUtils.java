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

package google.registry.flows.domain.token;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.pricing.PricingEngineProxy.isDomainPremium;
import static google.registry.util.CollectionUtils.isNullOrEmpty;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.net.InternetDomainName;
import google.registry.flows.EppException;
import google.registry.flows.EppException.AssociationProhibitsOperationException;
import google.registry.flows.EppException.AuthorizationErrorException;
import google.registry.flows.EppException.StatusProhibitsOperationException;
import google.registry.model.billing.BillingBase;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenBehavior;
import google.registry.model.domain.token.AllocationTokenExtension;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.model.tld.Tld;
import google.registry.persistence.VKey;
import java.util.Map;
import java.util.Optional;
import org.joda.time.DateTime;

/** Utility functions for dealing with {@link AllocationToken}s in domain flows. */
public class AllocationTokenFlowUtils {

  private AllocationTokenFlowUtils() {}

  /** Redeems a SINGLE_USE {@link AllocationToken}, returning the redeemed copy. */
  public static AllocationToken redeemToken(
      AllocationToken token, HistoryEntryId redemptionHistoryId) {
    checkArgument(
        token.getTokenType().isOneTimeUse(), "Only SINGLE_USE tokens can be marked as redeemed");
    return token.asBuilder().setRedemptionHistoryId(redemptionHistoryId).build();
  }

  /** Don't apply discounts on premium domains if the token isn't configured that way. */
  public static boolean discountTokenInvalidForPremiumName(
      AllocationToken token, boolean isPremium) {
    return (token.getDiscountFraction() != 0.0 || token.getDiscountPrice().isPresent())
        && isPremium
        && !token.shouldDiscountPremiums();
  }

  /** Loads and verifies the allocation token if one is specified, otherwise does nothing. */
  public static Optional<AllocationToken> loadAllocationTokenFromExtension(
      String registrarId,
      String domainName,
      DateTime now,
      Optional<AllocationTokenExtension> extension)
      throws NonexistentAllocationTokenException, AllocationTokenInvalidException {
    if (extension.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        loadAndValidateToken(extension.get().getAllocationToken(), registrarId, domainName, now));
  }

  /**
   * Loads the relevant token, if present, for the given extension + request.
   *
   * <p>This may be the allocation token provided in the request, if it is present and valid for the
   * request. Otherwise, it may be a default allocation token if one is present and valid for the
   * request.
   */
  public static Optional<AllocationToken> loadTokenFromExtensionOrGetDefault(
      String registrarId,
      DateTime now,
      Optional<AllocationTokenExtension> extension,
      Tld tld,
      String domainName,
      CommandName commandName)
      throws NonexistentAllocationTokenException, AllocationTokenInvalidException {
    Optional<AllocationToken> fromExtension =
        loadAllocationTokenFromExtension(registrarId, domainName, now, extension);
    if (fromExtension.isPresent()
        && tokenIsValidAgainstDomain(
            InternetDomainName.from(domainName), fromExtension.get(), commandName, now)) {
      return fromExtension;
    }
    return checkForDefaultToken(tld, domainName, commandName, registrarId, now);
  }

  /** Verifies that the given domain can have a bulk pricing token removed from it. */
  public static void verifyBulkTokenAllowedOnDomain(
      Domain domain, Optional<AllocationToken> allocationToken) throws EppException {
    boolean domainHasBulkToken = domain.getCurrentBulkToken().isPresent();
    boolean hasRemoveBulkPricingToken =
        allocationToken.isPresent()
            && TokenBehavior.REMOVE_BULK_PRICING.equals(allocationToken.get().getTokenBehavior());

    if (hasRemoveBulkPricingToken && !domainHasBulkToken) {
      throw new RemoveBulkPricingTokenOnNonBulkPricingDomainException();
    } else if (!hasRemoveBulkPricingToken && domainHasBulkToken) {
      throw new MissingRemoveBulkPricingTokenOnBulkPricingDomainException();
    }
  }

  /**
   * Removes the bulk pricing token from the provided domain, if applicable.
   *
   * @param allocationToken the (possibly) REMOVE_BULK_PRICING token provided by the client.
   */
  public static Domain maybeApplyBulkPricingRemovalToken(
      Domain domain, Optional<AllocationToken> allocationToken) {
    if (allocationToken.isEmpty()
        || !TokenBehavior.REMOVE_BULK_PRICING.equals(allocationToken.get().getTokenBehavior())) {
      return domain;
    }

    BillingRecurrence newBillingRecurrence =
        tm().loadByKey(domain.getAutorenewBillingEvent())
            .asBuilder()
            .setRenewalPriceBehavior(BillingBase.RenewalPriceBehavior.DEFAULT)
            .setRenewalPrice(null)
            .build();

    // the Recurrence is reloaded later in the renew flow, so we synchronize changed
    // Recurrences with storage manually
    tm().put(newBillingRecurrence);
    tm().getEntityManager().flush();
    tm().getEntityManager().clear();

    // Remove current bulk token
    return domain
        .asBuilder()
        .setCurrentBulkToken(null)
        .setAutorenewBillingEvent(newBillingRecurrence.createVKey())
        .build();
  }

  /**
   * Checks if the given token is valid for the given request.
   *
   * <p>Note that if the token is not valid, that is not a catastrophic error -- we may move on to
   * trying a different token or skip token usage entirely.
   */
  @VisibleForTesting
  static boolean tokenIsValidAgainstDomain(
      InternetDomainName domainName, AllocationToken token, CommandName commandName, DateTime now) {
    if (discountTokenInvalidForPremiumName(token, isDomainPremium(domainName.toString(), now))) {
      return false;
    }
    if (!token.getAllowedEppActions().isEmpty()
        && !token.getAllowedEppActions().contains(commandName)) {
      return false;
    }
    if (!token.getAllowedTlds().isEmpty()
        && !token.getAllowedTlds().contains(domainName.parent().toString())) {
      return false;
    }
    return token.getDomainName().isEmpty()
        || token.getDomainName().get().equals(domainName.toString());
  }

  /**
   * Checks if there is a valid default token to be used for a domain create command.
   *
   * <p>If there is more than one valid default token for the registration, only the first valid
   * token found on the TLD's default token list will be returned.
   */
  private static Optional<AllocationToken> checkForDefaultToken(
      Tld tld, String domainName, CommandName commandName, String registrarId, DateTime now) {
    ImmutableList<VKey<AllocationToken>> tokensFromTld = tld.getDefaultPromoTokens();
    if (isNullOrEmpty(tokensFromTld)) {
      return Optional.empty();
    }
    Map<VKey<AllocationToken>, Optional<AllocationToken>> tokens =
        AllocationToken.getAll(tokensFromTld);
    checkState(
        !isNullOrEmpty(tokens), "Failure while loading default TLD tokens from the database");
    // Iterate over the list to maintain token ordering (since we return the first valid token)
    ImmutableList<AllocationToken> tokenList =
        tokensFromTld.stream()
            .map(tokens::get)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(toImmutableList());

    // Check if any of the tokens are valid for this domain registration
    for (AllocationToken token : tokenList) {
      try {
        validateTokenEntity(token, registrarId, domainName, now);
      } catch (AllocationTokenInvalidException e) {
        // Token is not valid for this registrar, etc. -- continue trying tokens
        continue;
      }
      if (tokenIsValidAgainstDomain(InternetDomainName.from(domainName), token, commandName, now)) {
        return Optional.of(token);
      }
    }
    // No valid default token found
    return Optional.empty();
  }

  /** Loads a given token and validates it against the registrar, time, etc */
  private static AllocationToken loadAndValidateToken(
      String token, String registrarId, String domainName, DateTime now)
      throws NonexistentAllocationTokenException, AllocationTokenInvalidException {
    if (Strings.isNullOrEmpty(token)) {
      // We load the token directly from the input XML. If it's null or empty we should throw
      // an NonexistentAllocationTokenException before the database load attempt fails.
      // See https://tools.ietf.org/html/draft-ietf-regext-allocation-token-04#section-2.1
      throw new NonexistentAllocationTokenException();
    }

    Optional<AllocationToken> maybeTokenEntity = AllocationToken.maybeGetStaticTokenInstance(token);
    if (maybeTokenEntity.isPresent()) {
      return maybeTokenEntity.get();
    }

    maybeTokenEntity = AllocationToken.get(VKey.create(AllocationToken.class, token));
    if (maybeTokenEntity.isEmpty()) {
      throw new NonexistentAllocationTokenException();
    }
    AllocationToken tokenEntity = maybeTokenEntity.get();
    validateTokenEntity(tokenEntity, registrarId, domainName, now);
    return tokenEntity;
  }

  private static void validateTokenEntity(
      AllocationToken token, String registrarId, String domainName, DateTime now)
      throws AllocationTokenInvalidException {
    if (token.isRedeemed()) {
      throw new AlreadyRedeemedAllocationTokenException();
    }
    if (!token.getAllowedRegistrarIds().isEmpty()
        && !token.getAllowedRegistrarIds().contains(registrarId)) {
      throw new AllocationTokenNotValidForRegistrarException();
    }
    // Tokens without status transitions will just have a single-entry NOT_STARTED map, so only
    // check the status transitions map if it's non-trivial.
    if (token.getTokenStatusTransitions().size() > 1
        && !AllocationToken.TokenStatus.VALID.equals(
            token.getTokenStatusTransitions().getValueAtTime(now))) {
      throw new AllocationTokenNotInPromotionException();
    }

    if (token.getDomainName().isPresent() && !token.getDomainName().get().equals(domainName)) {
      throw new AllocationTokenNotValidForDomainException();
    }
  }

  // Note: exception messages should be <= 32 characters long for domain check results

  /** The allocation token exists but is not valid, e.g. the wrong registrar. */
  public abstract static class AllocationTokenInvalidException
      extends StatusProhibitsOperationException {
    AllocationTokenInvalidException(String message) {
      super(message);
    }
  }

  /** The allocation token is not currently valid. */
  public static class AllocationTokenNotInPromotionException
      extends AllocationTokenInvalidException {
    AllocationTokenNotInPromotionException() {
      super("Alloc token not in promo period");
    }
  }

  /** The allocation token is not valid for this registrar. */
  public static class AllocationTokenNotValidForRegistrarException
      extends AllocationTokenInvalidException {
    AllocationTokenNotValidForRegistrarException() {
      super("Alloc token invalid for client");
    }
  }

  /** The allocation token was already redeemed. */
  public static class AlreadyRedeemedAllocationTokenException
      extends AllocationTokenInvalidException {
    AlreadyRedeemedAllocationTokenException() {
      super("Alloc token was already redeemed");
    }
  }

  /** The allocation token is not valid for this domain. */
  public static class AllocationTokenNotValidForDomainException
      extends AllocationTokenInvalidException {
    AllocationTokenNotValidForDomainException() {
      super("Alloc token invalid for domain");
    }
    }

  /** The allocation token is invalid. */
  public static class NonexistentAllocationTokenException extends AuthorizationErrorException {
    NonexistentAllocationTokenException() {
      super("The allocation token is invalid");
    }
  }

  /** The __REMOVE_BULK_PRICING__ token is missing on a bulk pricing domain command */
  public static class MissingRemoveBulkPricingTokenOnBulkPricingDomainException
      extends AssociationProhibitsOperationException {
    MissingRemoveBulkPricingTokenOnBulkPricingDomainException() {
      super("Domains that are inside bulk pricing cannot be explicitly renewed or transferred");
    }
  }

  /** The __REMOVE_BULK_PRICING__ token is not allowed on non bulk pricing domains */
  public static class RemoveBulkPricingTokenOnNonBulkPricingDomainException
      extends AssociationProhibitsOperationException {
    RemoveBulkPricingTokenOnNonBulkPricingDomainException() {
      super("__REMOVE_BULK_PRICING__ token is not allowed on non bulk pricing domains");
    }
  }
}
