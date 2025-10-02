// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.partition;
import static com.google.common.collect.Streams.stream;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.internal.Nullable;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.billing.BillingBase.RenewalPriceBehavior;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.RegistrationBehavior;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.domain.token.AllocationToken.TokenType;
import google.registry.tools.params.MoneyParameter;
import google.registry.tools.params.StringListParameter;
import google.registry.tools.params.TransitionListParameter.TokenStatusTransitions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.joda.money.Money;
import org.joda.time.DateTime;

/** Command to update existing {@link AllocationToken}s. */
@Parameters(
    separators = " =",
    commandDescription =
        "Updates AllocationTokens with the given prefix (or specified tokens), modifying some "
            + "subset of their allowed client IDs, allowed TLDs, discount fraction, or status "
            + "transitions")
final class UpdateAllocationTokensCommand extends UpdateOrDeleteAllocationTokensCommand {

  @Parameter(
      names = {"-c", "--allowed_client_ids"},
      description =
          "Comma-separated list of allowed client IDs. Use the empty string to clear the "
              + "existing list.",
      listConverter = StringListParameter.class)
  private List<String> allowedClientIds;

  @Parameter(
      names = {"-t", "--allowed_tlds"},
      description =
          "Comma-separated list of allowed TLDs. Use the empty string to clear the "
              + "existing list.",
      listConverter = StringListParameter.class)
  private List<String> allowedTlds;

  @Parameter(
      names = {"--allowed_epp_actions"},
      description =
          "Comma-separated list of allowed EPP actions. Use an empty string to clear the existing"
              + " list.",
      listConverter = StringListParameter.class)
  private List<String> allowedEppActions;

  @Parameter(
      names = {"--discount_fraction"},
      description =
          "A discount off the base price for the first year between 0.0 and 1.0. Default is 0.0, "
              + "i.e. no discount.")
  private Double discountFraction;

  @Parameter(
      names = {"--discount_premiums"},
      description =
          "Whether the discount is valid for premium names in addition to standard ones. Default"
              + " is false.",
      arity = 1)
  private Boolean discountPremiums;

  @Parameter(
      names = {"--discount_price"},
      description =
          "A discount that allows the setting of promotional prices. This field is different from "
              + "{@code discountFraction} because the price set here is treated as the domain "
              + "price, versus {@code discountFraction} that applies a fraction discount to the "
              + "domain base price. Use CURRENCY PRICE format, example: USD 777.99",
      converter = MoneyParameter.class,
      validateWith = MoneyParameter.class)
  private Money discountPrice;

  @Parameter(
      names = {"-y", "--discount_years"},
      description = "The number of years the discount applies for. Default is 1, max value is 10.")
  private Integer discountYears;

  @Parameter(
      names = "--token_status_transitions",
      converter = TokenStatusTransitions.class,
      validateWith = TokenStatusTransitions.class,
      description =
          "Comma-delimited list of token status transitions effective on specific dates, of the "
              + "form <time>=<status>[,<time>=<status>]* where each status represents the status.")
  private ImmutableSortedMap<DateTime, TokenStatus> tokenStatusTransitions;

  @Parameter(
      names = {"--renewal_price_behavior"},
      description =
          "The type of renewal price behavior, either DEFAULT (default), NONPREMIUM, or SPECIFIED."
              + " This indicates how a domain should be charged for renewal. By default, a domain"
              + " will be renewed at the renewal price from the pricing engine. If the renewal"
              + " price behavior is set to SPECIFIED, it means that the renewal cost will be equal"
              + " to the provided renewal price.")
  @Nullable
  private RenewalPriceBehavior renewalPriceBehavior;

  @Parameter(
      names = {"--renewal_price"},
      description = "The renewal price amount iff the renewal price behavior is SPECIFIED.")
  @Nullable
  private Money renewalPrice;

  @Parameter(
      names = {"--registration_behavior"},
      description =
          "Any special registration behavior, including DEFAULT (no special behavior),"
              + " BYPASS_TLD_STATE (allow registrations during e.g. QUIET_PERIOD), and"
              + " ANCHOR_TENANT (used for anchor tenant registrations")
  @Nullable
  private RegistrationBehavior registrationBehavior;

  private static final int BATCH_SIZE = 20;
  private static final Joiner JOINER = Joiner.on(", ");

  private ImmutableSet<AllocationToken> tokensToSave;
  private boolean endToken = false;

  @Override
  public void init() {
    if (tokenStatusTransitions != null
        && (tokenStatusTransitions.containsValue(TokenStatus.ENDED)
            || tokenStatusTransitions.containsValue(TokenStatus.CANCELLED))) {
      endToken = true;
    }

    GenerateAllocationTokensCommand.verifyAllRegistrarIdsExist(allowedClientIds);
    GenerateAllocationTokensCommand.verifyAllTldsExist(allowedTlds);

    tokensToSave =
        tm().transact(
                () ->
                    tm().loadByKeys(getTokenKeys(tokens, prefix)).values().stream()
                        .collect(toImmutableMap(Function.identity(), this::updateToken))
                        .entrySet()
                        .stream()
                        .filter(
                            entry ->
                                !entry
                                    .getKey()
                                    .equals(entry.getValue())) // only update changed tokens
                        .map(Map.Entry::getValue)
                        .collect(toImmutableSet()));
  }

  @Override
  public String prompt() {
    return String.format("Update %d tokens?", tokensToSave.size());
  }

  @Override
  protected String execute() {
    long numUpdated =
        stream(partition(tokensToSave, BATCH_SIZE))
            .mapToLong(batch -> tm().transact(() -> saveBatch(ImmutableList.copyOf(batch))))
            .sum();
    return String.format("Updated %d tokens in total.", numUpdated);
  }

  private AllocationToken updateToken(AllocationToken original) {
    if (endToken && original.getTokenType().equals(TokenType.BULK_PRICING)) {
      Long domainsInBulkPackage =
          tm().query("SELECT COUNT(*) FROM Domain WHERE currentBulkToken = :token", Long.class)
              .setParameter("token", original.createVKey())
              .getSingleResult();

      checkArgument(
          domainsInBulkPackage == 0,
          "Bulk token %s can not end its promotion because it still has %s domains in the"
              + " promotion",
          original.getToken(),
          domainsInBulkPackage);
    }
    AllocationToken.Builder builder = original.asBuilder();
    Optional.ofNullable(allowedClientIds)
        .ifPresent(clientIds -> builder.setAllowedRegistrarIds(ImmutableSet.copyOf(clientIds)));
    Optional.ofNullable(allowedTlds)
        .ifPresent(tlds -> builder.setAllowedTlds(ImmutableSet.copyOf(tlds)));
    Optional.ofNullable(allowedEppActions)
        .ifPresent(
            eppActions ->
                builder.setAllowedEppActions(
                    eppActions.stream()
                        .map(CommandName::parseKnownCommand)
                        .collect(toImmutableSet())));
    Optional.ofNullable(discountFraction).ifPresent(builder::setDiscountFraction);
    Optional.ofNullable(discountPremiums).ifPresent(builder::setDiscountPremiums);
    Optional.ofNullable(discountPrice).ifPresent(builder::setDiscountPrice);
    Optional.ofNullable(discountYears).ifPresent(builder::setDiscountYears);
    Optional.ofNullable(tokenStatusTransitions).ifPresent(builder::setTokenStatusTransitions);

    if (renewalPriceBehavior != null
        && renewalPriceBehavior != original.getRenewalPriceBehavior()) {
      builder.setRenewalPrice(null);
    }
    Optional.ofNullable(renewalPriceBehavior).ifPresent(builder::setRenewalPriceBehavior);
    Optional.ofNullable(renewalPrice).ifPresent(builder::setRenewalPrice);
    Optional.ofNullable(registrationBehavior).ifPresent(builder::setRegistrationBehavior);
    return builder.build();
  }

  private long saveBatch(ImmutableList<AllocationToken> batch) {
    if (!dryRun) {
      tm().putAll(batch);
    }
    printStream.printf(
        "%s tokens: %s\n",
        dryRun ? "Would update" : "Updated",
        JOINER.join(
            tokensToSave.stream()
                .map(AllocationToken::getToken)
                .sorted()
                .collect(toImmutableSet())));
    return batch.size();
  }
}
