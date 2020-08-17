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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.partition;
import static com.google.common.collect.Streams.stream;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.tools.params.TransitionListParameter.TokenStatusTransitions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
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
      names = {"--allowed_client_ids"},
      description =
          "Comma-separated list of allowed client IDs. Use the empty string to clear the "
              + "existing list.")
  private List<String> allowedClientIds;

  @Parameter(
      names = {"--allowed_tlds"},
      description =
          "Comma-separated list of allowed TLDs. Use the empty string to clear the "
              + "existing list.")
  private List<String> allowedTlds;

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
      names = {"--discount_years"},
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

  private static final int BATCH_SIZE = 20;
  private static final Joiner JOINER = Joiner.on(", ");

  private ImmutableSet<AllocationToken> tokensToSave;

  @Override
  public void init() {
    // A single entry with the empty string means that the user passed an empty argument to the
    // lists, so we should clear them
    if (ImmutableList.of("").equals(allowedClientIds)) {
      allowedClientIds = ImmutableList.of();
    }
    if (ImmutableList.of("").equals(allowedTlds)) {
      allowedTlds = ImmutableList.of();
    }

    tokensToSave =
        ofy().load().keys(getTokenKeys()).values().stream()
            .collect(toImmutableMap(Function.identity(), this::updateToken))
            .entrySet()
            .stream()
            .filter(entry -> !entry.getKey().equals(entry.getValue())) // only update changed tokens
            .map(Map.Entry::getValue)
            .collect(toImmutableSet());
  }

  @Override
  public String prompt() {
    return String.format("Update %d tokens?", tokensToSave.size());
  }

  @Override
  protected String execute() {
    long numUpdated =
        stream(partition(tokensToSave, BATCH_SIZE))
            .mapToLong(batch -> tm().transact(() -> saveBatch(batch)))
            .sum();
    return String.format("Updated %d tokens in total.", numUpdated);
  }

  private AllocationToken updateToken(AllocationToken original) {
    AllocationToken.Builder builder = original.asBuilder();
    Optional.ofNullable(allowedClientIds)
        .ifPresent(clientIds -> builder.setAllowedRegistrarIds(ImmutableSet.copyOf(clientIds)));
    Optional.ofNullable(allowedTlds)
        .ifPresent(tlds -> builder.setAllowedTlds(ImmutableSet.copyOf(tlds)));
    Optional.ofNullable(discountFraction).ifPresent(builder::setDiscountFraction);
    Optional.ofNullable(discountPremiums).ifPresent(builder::setDiscountPremiums);
    Optional.ofNullable(discountYears).ifPresent(builder::setDiscountYears);
    Optional.ofNullable(tokenStatusTransitions).ifPresent(builder::setTokenStatusTransitions);
    return builder.build();
  }

  private long saveBatch(List<AllocationToken> batch) {
    if (!dryRun) {
      ofy().save().entities(batch);
    }
    System.out.printf(
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
