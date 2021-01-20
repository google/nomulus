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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.net.InternetDomainName;
import google.registry.flows.EppException;
import google.registry.flows.EppException.AssociationProhibitsOperationException;
import google.registry.flows.EppException.AuthorizationErrorException;
import google.registry.flows.EppException.StatusProhibitsOperationException;
import google.registry.model.domain.DomainCommand;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.domain.token.AllocationToken.TokenType;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
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
   * Loads an allocation token given a string and verifies that the token is valid for the domain
   * create request.
   *
   * @return the loaded {@link AllocationToken} for that string.
   * @throws EppException if the token doesn't exist, is already redeemed, or is otherwise invalid
   *     for this request.
   */
  public AllocationToken loadTokenAndValidateDomainCreate(
      DomainCommand.Create command, String token, Registry registry, String clientId, DateTime now)
      throws EppException {
    AllocationToken tokenEntity = loadToken(token);
    validateToken(
        InternetDomainName.from(command.getFullyQualifiedDomainName()), tokenEntity, clientId, now);
    return tokenCustomLogic.validateToken(command, tokenEntity, registry, clientId, now);
  }

  /**
   * Checks if the allocation token applies to the given domain names, used for domain checks.
   *
   * @return A map of domain names to domain check error response messages. If a message is present
   *     for a a given domain then it does not validate with this allocation token; domains that do
   *     validate have blank messages (i.e. no error).
   */
  public AllocationTokenDomainCheckResults checkDomainsWithToken(
      List<InternetDomainName> domainNames, String token, String clientId, DateTime now) {
    // If the token is completely invalid, return the error message for all domain names
    AllocationToken tokenEntity;
    try {
      tokenEntity = loadToken(token);
    } catch (EppException e) {
      return AllocationTokenDomainCheckResults.create(
          Optional.empty(),
          ImmutableMap.copyOf(Maps.toMap(domainNames, ignored -> e.getMessage())));
    }

    // If the token is only invalid for some domain names (e.g. an invalid TLD), include those error
    // results for only those domain names
    ImmutableList.Builder<InternetDomainName> validDomainNames = new ImmutableList.Builder<>();
    ImmutableMap.Builder<InternetDomainName, String> resultsBuilder = new ImmutableMap.Builder<>();
    for (InternetDomainName domainName : domainNames) {
      try {
        validateToken(domainName, tokenEntity, clientId, now);
        validDomainNames.add(domainName);
      } catch (EppException e) {
        resultsBuilder.put(domainName, e.getMessage());
      }
    }

    // For all valid domain names, run the custom logic and include the results
    resultsBuilder.putAll(
        tokenCustomLogic.checkDomainsWithToken(
            validDomainNames.build(), tokenEntity, clientId, now));
    return AllocationTokenDomainCheckResults.create(
        Optional.of(tokenEntity), resultsBuilder.build());
  }

  /** Redeems a SINGLE_USE {@link AllocationToken}, returning the redeemed copy. */
  public AllocationToken redeemToken(
      AllocationToken token, VKey<? extends HistoryEntry> redemptionHistoryEntry) {
    checkArgument(
        TokenType.SINGLE_USE.equals(token.getTokenType()),
        "Only SINGLE_USE tokens can be marked as redeemed");
    return token.asBuilder().setRedemptionHistoryEntry(redemptionHistoryEntry).build();
  }

  /**
   * Validates a given token. The token could be invalid if it has allowed client IDs or TLDs that
   * do not include this client ID / TLD, or if the token has a promotion that is not currently
   * running.
   *
   * @throws EppException if the token is invalid in any way
   */
  private void validateToken(
      InternetDomainName domainName, AllocationToken token, String clientId, DateTime now)
      throws EppException {
    if (!token.getAllowedRegistrarIds().isEmpty()
        && !token.getAllowedRegistrarIds().contains(clientId)) {
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

  /** Loads a given token and validates that it is not redeemed */
  private AllocationToken loadToken(String token) throws EppException {
    if (Strings.isNullOrEmpty(token)) {
      // We load the token directly from the input XML. If it's null or empty we should throw
      // an InvalidAllocationTokenException before the Datastore load attempt fails.
      // See https://tools.ietf.org/html/draft-ietf-regext-allocation-token-04#section-2.1
      throw new InvalidAllocationTokenException();
    }
    Optional<AllocationToken> maybeTokenEntity =
        tm().loadByKeyIfPresent(VKey.create(AllocationToken.class, token));
    if (!maybeTokenEntity.isPresent()) {
      throw new InvalidAllocationTokenException();
    }
    if (maybeTokenEntity.get().isRedeemed()) {
      throw new AlreadyRedeemedAllocationTokenException();
    }
    return maybeTokenEntity.get();
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

  /** The allocation token is invalid. */
  public static class InvalidAllocationTokenException extends AuthorizationErrorException {
    InvalidAllocationTokenException() {
      super("The allocation token is invalid");
    }
  }
}
