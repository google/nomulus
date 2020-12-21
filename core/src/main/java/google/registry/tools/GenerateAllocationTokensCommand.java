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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Queues.newArrayDeque;
import static com.google.common.collect.Sets.difference;
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.model.domain.token.AllocationToken.TokenType.UNLIMITED_USE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.StringGenerator.DEFAULT_PASSWORD_LENGTH;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.appengine.tools.remoteapi.RemoteApiException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.common.io.Files;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.domain.token.AllocationToken.TokenType;
import google.registry.persistence.VKey;
import google.registry.tools.params.TransitionListParameter.TokenStatusTransitions;
import google.registry.util.CollectionUtils;
import google.registry.util.NonFinalForTesting;
import google.registry.util.Retrier;
import google.registry.util.StringGenerator;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Named;
import org.joda.time.DateTime;

/** Command to generate and persist {@link AllocationToken}s. */
@Parameters(
    separators = " =",
    commandDescription =
        "Generates and persists the given number of AllocationTokens, "
            + "printing each token to stdout.")
@NonFinalForTesting
class GenerateAllocationTokensCommand implements CommandWithRemoteApi {

  @Parameter(
      names = {"--tokens"},
      description =
          "Comma-separated list of exact tokens to generate, otherwise use --prefix or "
              + "--domain_names_file to generate tokens if the exact strings do not matter")
  private List<String> tokenStrings;

  @Parameter(
      names = {"-p", "--prefix"},
      description = "Allocation token prefix; defaults to blank")
  private String prefix = "";

  @Parameter(
      names = {"-n", "--number"},
      description = "The number of tokens to generate")
  private long numTokens;

  @Parameter(
      names = {"-d", "--domain_names_file"},
      description = "A file with a list of newline-delimited domain names to create tokens for")
  private String domainNamesFile;

  @Parameter(
      names = {"-l", "--length"},
      description =
          "The length of each token, exclusive of the prefix (if specified); defaults to 16")
  private int tokenLength = DEFAULT_PASSWORD_LENGTH;

  @Parameter(
      names = {"-t", "--type"},
      description = "Type of type token, either SINGLE_USE (default) or UNLIMITED_USE")
  private TokenType tokenType;

  @Parameter(
      names = {"--allowed_client_ids"},
      description = "Comma-separated list of allowed client IDs, or null if all are allowed")
  private List<String> allowedClientIds;

  @Parameter(
      names = {"--allowed_tlds"},
      description = "Comma-separated list of allowed TLDs, or null if all are allowed")
  private List<String> allowedTlds;

  @Parameter(
      names = {"--discount_fraction"},
      description =
          "A discount off the base price for the first year between 0.0 and 1.0. Default is 0.0,"
              + " i.e. no discount.")
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
          "Comma-delimited list of token status transitions effective on specific dates, of the"
              + " form <time>=<status>[,<time>=<status>]* where each status represents the status.")
  private ImmutableSortedMap<DateTime, TokenStatus> tokenStatusTransitions;

  @Parameter(
      names = {"--dry_run"},
      description = "Do not actually persist the tokens; defaults to false")
  boolean dryRun;

  @Inject
  @Named("base58StringGenerator")
  StringGenerator stringGenerator;

  @Inject Retrier retrier;

  private static final int BATCH_SIZE = 20;
  private static final Joiner SKIP_NULLS = Joiner.on(',').skipNulls();

  @Override
  public void run() throws IOException {
    verifyInput();
    if (tokenStrings != null) {
      numTokens = tokenStrings.size();
    }
    Deque<String> domainNames;
    if (domainNamesFile == null) {
      domainNames = null;
    } else {
      domainNames =
          newArrayDeque(
              Splitter.on('\n')
                  .omitEmptyStrings()
                  .trimResults()
                  .split(Files.asCharSource(new File(domainNamesFile), UTF_8).read()));
      numTokens = domainNames.size();
    }

    int tokensSaved = 0;
    do {
      ImmutableSet<AllocationToken> tokens =
          getNextTokenBatch(tokensSaved)
              .map(
                  t -> {
                    AllocationToken.Builder token =
                        new AllocationToken.Builder()
                            .setToken(t)
                            .setTokenType(tokenType == null ? SINGLE_USE : tokenType)
                            .setAllowedRegistrarIds(
                                ImmutableSet.copyOf(nullToEmpty(allowedClientIds)))
                            .setAllowedTlds(ImmutableSet.copyOf(nullToEmpty(allowedTlds)));
                    Optional.ofNullable(discountFraction).ifPresent(token::setDiscountFraction);
                    Optional.ofNullable(discountPremiums).ifPresent(token::setDiscountPremiums);
                    Optional.ofNullable(discountYears).ifPresent(token::setDiscountYears);
                    Optional.ofNullable(tokenStatusTransitions)
                        .ifPresent(token::setTokenStatusTransitions);
                    Optional.ofNullable(domainNames)
                        .ifPresent(d -> token.setDomainName(d.removeFirst()));
                    return token.build();
                  })
              .collect(toImmutableSet());
      // Wrap in a retrier to deal with transient 404 errors (thrown as RemoteApiExceptions).
      tokensSaved += retrier.callWithRetry(() -> saveTokens(tokens), RemoteApiException.class);
    } while (tokensSaved < numTokens);
  }

  private void verifyInput() {
    int inputMethods = 0;
    if (numTokens > 0) {
      inputMethods++;
    }
    if (domainNamesFile != null) {
      inputMethods++;
    }
    if (tokenStrings != null && !tokenStrings.isEmpty()) {
      inputMethods++;
    }
    checkArgument(
        inputMethods == 1,
        "Must specify exactly one of '--number', '--domain_names_file', and '--tokens'");

    checkArgument(
        tokenLength > 0,
        "Token length should not be 0. To generate exact tokens, use the --tokens parameter.");

    checkArgument(
        !(UNLIMITED_USE.equals(tokenType) && CollectionUtils.isNullOrEmpty(tokenStatusTransitions)),
        "For UNLIMITED_USE tokens, must specify --token_status_transitions");

    // A list consisting solely of the empty string means user error when formatting parameters
    checkArgument(
        !ImmutableList.of("").equals(allowedClientIds),
        "Either omit --allowed_client_ids if all registrars are allowed, or include a"
            + " comma-separated list");

    checkArgument(
        !ImmutableList.of("").equals(allowedTlds),
        "Either omit --allowed_tlds if all TLDs are allowed, or include a comma-separated list");

    if (tokenStrings != null) {
      verifyTokenStringsDoNotExist();
    }
  }

  private void verifyTokenStringsDoNotExist() {
    ImmutableSet<String> existingTokenStrings =
        getExistingTokenStrings(ImmutableSet.copyOf(tokenStrings));
    checkArgument(
        existingTokenStrings.isEmpty(),
        String.format(
            "Cannot create specified tokens; the following tokens already exist: %s",
            existingTokenStrings));
  }

  private Stream<String> getNextTokenBatch(int tokensSaved) {
    if (tokenStrings != null) {
      return Streams.stream(Iterables.limit(Iterables.skip(tokenStrings, tokensSaved), BATCH_SIZE));
    } else {
      return generateTokens(BATCH_SIZE).stream().limit(numTokens - tokensSaved);
    }
  }

  @VisibleForTesting
  int saveTokens(final ImmutableSet<AllocationToken> tokens) {
    Collection<AllocationToken> savedTokens;
    if (dryRun) {
      savedTokens = tokens;
    } else {
      transactIfJpaTm(() -> tm().transact(() -> tm().putAll(tokens)));
      savedTokens = tm().transact(() -> tm().loadAll(tokens));
    }
    savedTokens.forEach(
        t -> System.out.println(SKIP_NULLS.join(t.getDomainName().orElse(null), t.getToken())));
    return savedTokens.size();
  }

  /**
   * This function generates at MOST {@code count} tokens, filtering out already-existing token
   * strings.
   *
   * <p>Note that in the incredibly rare case that all generated tokens already exist, this function
   * may return an empty set.
   */
  private ImmutableSet<String> generateTokens(int count) {
    ImmutableSet<String> candidates =
        stringGenerator.createStrings(tokenLength, count).stream()
            .map(s -> prefix + s)
            .collect(toImmutableSet());
    ImmutableSet<String> existingTokenStrings = getExistingTokenStrings(candidates);
    return ImmutableSet.copyOf(difference(candidates, existingTokenStrings));
  }

  private ImmutableSet<String> getExistingTokenStrings(ImmutableSet<String> candidates) {
    ImmutableSet<VKey<AllocationToken>> existingTokenKeys =
        candidates.stream()
            .map(input -> VKey.create(AllocationToken.class, input))
            .collect(toImmutableSet());
    return transactIfJpaTm(
        () ->
            tm().load(existingTokenKeys).values().stream()
                .map(AllocationToken::getToken)
                .collect(toImmutableSet()));
  }
}
