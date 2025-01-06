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

package google.registry.flows.domain.token;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.pricing.PricingEngineProxy.isDomainPremium;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.net.InternetDomainName;
import google.registry.flows.EppException;
import google.registry.flows.EppException.AssociationProhibitsOperationException;
import google.registry.flows.EppException.AuthorizationErrorException;
import google.registry.flows.EppException.StatusProhibitsOperationException;
import google.registry.flows.domain.DomainPricingLogic.AllocationTokenInvalidForPremiumNameException;
import google.registry.model.billing.BillingBase.RenewalPriceBehavior;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainCommand;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenBehavior;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.domain.token.AllocationTokenExtension;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.model.tld.Tld;
import google.registry.persistence.VKey;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.DateTime;

/** Utility functions for dealing with {@link AllocationToken}s in domain flows. */
public class AllocationTokenFlowUtils {

  private final AllocationTokenCustomLogic tokenCustomLogic;

  @Inject
  AllocationTokenFlowUtils(AllocationTokenCustomLogic tokenCustomLogic) {
    this.tokenCustomLogic = tokenCustomLogic;
  }

  /**
   * Checks if the allocation token applies to the given domain names, used for domain checks.
   *
   * @return A map of domain names to domain check error response messages. If a message is present
   *     for a a given domain then it does not validate with this allocation token; domains that do
   *     validate have blank messages (i.e. no error).
   */
  public AllocationTokenDomainCheckResults checkDomainsWithToken(
      List<InternetDomainName> domainNames, String token, String registrarId, DateTime now) {
    // If the token is completely invalid, return the error message for all domain names
    AllocationToken tokenEntity;
    try {
      tokenEntity = loadToken(token);
    } catch (EppException e) {
      return new AllocationTokenDomainCheckResults(
          Optional.empty(), Maps.toMap(domainNames, ignored -> e.getMessage()));
    }

    // If the token is only invalid for some domain names (e.g. an invalid TLD), include those error
    // results for only those domain names
    ImmutableList.Builder<InternetDomainName> validDomainNames = new ImmutableList.Builder<>();
    ImmutableMap.Builder<InternetDomainName, String> resultsBuilder = new ImmutableMap.Builder<>();
    for (InternetDomainName domainName : domainNames) {
      try {
        validateToken(
            domainName,
            tokenEntity,
            CommandName.CREATE,
            registrarId,
            isDomainPremium(domainName.toString(), now),
            now);
        validDomainNames.add(domainName);
      } catch (EppException e) {
        resultsBuilder.put(domainName, e.getMessage());
      }
    }

    // For all valid domain names, run the custom logic and include the results
    resultsBuilder.putAll(
        tokenCustomLogic.checkDomainsWithToken(
            validDomainNames.build(), tokenEntity, registrarId, now));
    return new AllocationTokenDomainCheckResults(Optional.of(tokenEntity), resultsBuilder.build());
  }

  /** Redeems a SINGLE_USE {@link AllocationToken}, returning the redeemed copy. */
  public AllocationToken redeemToken(AllocationToken token, HistoryEntryId redemptionHistoryId) {
    checkArgument(
        token.getTokenType().isOneTimeUse(), "Only SINGLE_USE tokens can be marked as redeemed");
    return token.asBuilder().setRedemptionHistoryId(redemptionHistoryId).build();
  }

  /**
   * Validates a given token. The token could be invalid if it has allowed client IDs or TLDs that
   * do not include this client ID / TLD, or if the token has a promotion that is not currently
   * running, or the token is not valid for a premium name when necessary.
   *
   * @throws EppException if the token is invalid in any way
   */
  public static void validateToken(
      InternetDomainName domainName,
      AllocationToken token,
      CommandName commandName,
      String registrarId,
      boolean isPremium,
      DateTime now)
      throws EppException {

    // Only tokens with default behavior require validation
    if (!TokenBehavior.DEFAULT.equals(token.getTokenBehavior())) {
      return;
    }
    validateTokenForPossiblePremiumName(Optional.of(token), isPremium);
    if (!token.getAllowedEppActions().isEmpty()
        && !token.getAllowedEppActions().contains(commandName)) {
      throw new AllocationTokenNotValidForCommandException();
    }
    if (!token.getAllowedRegistrarIds().isEmpty()
        && !token.getAllowedRegistrarIds().contains(registrarId)) {
      throw new AllocationTokenNotValidForRegistrarException();
    }
    if (!token.getAllowedTlds().isEmpty()
        && !token.getAllowedTlds().contains(domainName.parent().toString())) {
      throw new AllocationTokenNotValidForTldException();
    }
    if (token.getDomainName().isPresent()
        && !token.getDomainName().get().equals(domainName.toString())) {
      throw new AllocationTokenNotValidForDomainException();
    }
    // Tokens without status transitions will just have a single-entry NOT_STARTED map, so only
    // check the status transitions map if it's non-trivial.
    if (token.getTokenStatusTransitions().size() > 1
        && !TokenStatus.VALID.equals(token.getTokenStatusTransitions().getValueAtTime(now))) {
      throw new AllocationTokenNotInPromotionException();
    }
  }

  /** Validates that the given token is valid for a premium name if the name is premium. */
  public static void validateTokenForPossiblePremiumName(
      Optional<AllocationToken> token, boolean isPremium)
      throws AllocationTokenInvalidForPremiumNameException {
    if (token.isPresent()
        && (token.get().getDiscountFraction() != 0.0 || token.get().getDiscountPrice().isPresent())
        && isPremium
        && !token.get().shouldDiscountPremiums()) {
      throw new AllocationTokenInvalidForPremiumNameException();
    }
  }

  /** Loads a given token and validates that it is not redeemed */
  private static AllocationToken loadToken(String token) throws EppException {
    if (Strings.isNullOrEmpty(token)) {
      // We load the token directly from the input XML. If it's null or empty we should throw
      // an InvalidAllocationTokenException before the database load attempt fails.
      // See https://tools.ietf.org/html/draft-ietf-regext-allocation-token-04#section-2.1
      throw new InvalidAllocationTokenException();
    }

    Optional<AllocationToken> maybeTokenEntity = AllocationToken.maybeGetStaticTokenInstance(token);
    if (maybeTokenEntity.isPresent()) {
      return maybeTokenEntity.get();
    }

    // TODO(b/368069206): `reTransact` needed by tests only.
    maybeTokenEntity =
        tm().reTransact(() -> tm().loadByKeyIfPresent(VKey.create(AllocationToken.class, token)));

    if (maybeTokenEntity.isEmpty()) {
      throw new InvalidAllocationTokenException();
    }
    if (maybeTokenEntity.get().isRedeemed()) {
      throw new AlreadyRedeemedAllocationTokenException();
    }
    return maybeTokenEntity.get();
  }

  /** Verifies and returns the allocation token if one is specified, otherwise does nothing. */
  public Optional<AllocationToken> verifyAllocationTokenCreateIfPresent(
      DomainCommand.Create command,
      Tld tld,
      String registrarId,
      DateTime now,
      Optional<AllocationTokenExtension> extension)
      throws EppException {
    if (extension.isEmpty()) {
      return Optional.empty();
    }
    AllocationToken tokenEntity = loadToken(extension.get().getAllocationToken());
    validateToken(
        InternetDomainName.from(command.getDomainName()),
        tokenEntity,
        CommandName.CREATE,
        registrarId,
        isDomainPremium(command.getDomainName(), now),
        now);
    return Optional.of(tokenCustomLogic.validateToken(command, tokenEntity, tld, registrarId, now));
  }

  /** Verifies and returns the allocation token if one is specified, otherwise does nothing. */
  public Optional<AllocationToken> verifyAllocationTokenIfPresent(
      Domain existingDomain,
      Tld tld,
      String registrarId,
      DateTime now,
      CommandName commandName,
      Optional<AllocationTokenExtension> extension)
      throws EppException {
    if (extension.isEmpty()) {
      return Optional.empty();
    }
    AllocationToken tokenEntity = loadToken(extension.get().getAllocationToken());
    validateToken(
        InternetDomainName.from(existingDomain.getDomainName()),
        tokenEntity,
        commandName,
        registrarId,
        isDomainPremium(existingDomain.getDomainName(), now),
        now);
    return Optional.of(
        tokenCustomLogic.validateToken(existingDomain, tokenEntity, tld, registrarId, now));
  }

  public static void verifyTokenAllowedOnDomain(
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

  public static Domain maybeApplyBulkPricingRemovalToken(
      Domain domain, Optional<AllocationToken> allocationToken) {
    if (allocationToken.isEmpty()
        || !TokenBehavior.REMOVE_BULK_PRICING.equals(allocationToken.get().getTokenBehavior())) {
      return domain;
    }

    BillingRecurrence newBillingRecurrence =
        tm().loadByKey(domain.getAutorenewBillingEvent())
            .asBuilder()
            .setRenewalPriceBehavior(RenewalPriceBehavior.DEFAULT)
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

  // Note: exception messages should be <= 32 characters long for domain check results

  /** The allocation token is not currently valid. */
  public static class AllocationTokenNotInPromotionException
      extends StatusProhibitsOperationException {
    AllocationTokenNotInPromotionException() {
      super("Alloc token not in promo period");
    }
  }

  /** The allocation token is not valid for this TLD. */
  public static class AllocationTokenNotValidForTldException
      extends AssociationProhibitsOperationException {
    AllocationTokenNotValidForTldException() {
      super("Alloc token invalid for TLD");
    }
  }

  /** The allocation token is not valid for this domain. */
  public static class AllocationTokenNotValidForDomainException
      extends AssociationProhibitsOperationException {
    AllocationTokenNotValidForDomainException() {
      super("Alloc token invalid for domain");
    }
  }

  /** The allocation token is not valid for this registrar. */
  public static class AllocationTokenNotValidForRegistrarException
      extends AssociationProhibitsOperationException {
    AllocationTokenNotValidForRegistrarException() {
      super("Alloc token invalid for client");
    }
  }

  /** The allocation token was already redeemed. */
  public static class AlreadyRedeemedAllocationTokenException
      extends AssociationProhibitsOperationException {
    AlreadyRedeemedAllocationTokenException() {
      super("Alloc token was already redeemed");
    }
  }

  /** The allocation token is not valid for this EPP command. */
  public static class AllocationTokenNotValidForCommandException
      extends AssociationProhibitsOperationException {
    AllocationTokenNotValidForCommandException() {
      super("Allocation token not valid for the EPP command");
    }
  }

  /** The allocation token is invalid. */
  public static class InvalidAllocationTokenException extends AuthorizationErrorException {
    InvalidAllocationTokenException() {
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
