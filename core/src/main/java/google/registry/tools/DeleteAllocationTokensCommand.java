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

package google.registry.tools;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.partition;
import static com.google.common.collect.Streams.stream;
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import google.registry.model.domain.token.AllocationToken;
import google.registry.persistence.VKey;
import java.util.List;

/**
 * Command to delete unused {@link AllocationToken}s.
 *
 * <p>Note that all multi-use tokens and redeemed single-use tokens cannot be deleted.
 */
@Parameters(
    separators = " =",
    commandDescription =
        "Deletes the unused AllocationTokens with a given prefix (or specified tokens).")
final class DeleteAllocationTokensCommand extends UpdateOrDeleteAllocationTokensCommand {

  @Parameter(
      names = {"--with_domains"},
      description = "Allow deletion of allocation tokens with specified domains; defaults to false")
  private boolean withDomains;

  private static final int BATCH_SIZE = 20;
  private static final Joiner JOINER = Joiner.on(", ");

  private ImmutableSet<VKey<AllocationToken>> tokensToDelete;

  @Override
  public void init() {
    tokensToDelete = getTokenKeys();
  }

  @Override
  protected String prompt() {
    return String.format(
        "Found %d unused tokens starting with '%s' to delete.", tokensToDelete.size(), prefix);
  }

  @Override
  protected String execute() {
    long numDeleted =
        stream(partition(tokensToDelete, BATCH_SIZE))
            .mapToLong(batch -> tm().transact(() -> deleteBatch(batch)))
            .sum();
    return String.format("Deleted %d tokens in total.", numDeleted);
  }

  /** Deletes a (filtered) batch of AllocationTokens and returns how many were deleted. */
  private long deleteBatch(List<VKey<AllocationToken>> batch) {
    // Load the tokens in the same transaction as they are deleted to verify they weren't redeemed
    // since the query ran. This also filters out per-domain tokens if they're not to be deleted.
    ImmutableSet<VKey<AllocationToken>> tokensToDelete =
        tm().load(batch).values().stream()
            .filter(t -> withDomains || t.getDomainName().isEmpty())
            .filter(t -> SINGLE_USE.equals(t.getTokenType()))
            .filter(t -> !t.isRedeemed())
            .map(AllocationToken::createVKey)
            .collect(toImmutableSet());
    if (!dryRun) {
      tm().delete(tokensToDelete);
    }
    System.out.printf(
        "%s tokens: %s\n",
        dryRun ? "Would delete" : "Deleted",
        JOINER.join(
            tokensToDelete.stream().map(VKey::getSqlKey).sorted().collect(toImmutableList())));
    return tokensToDelete.size();
  }
}
