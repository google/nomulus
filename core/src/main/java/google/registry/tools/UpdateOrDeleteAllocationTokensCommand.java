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
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;

import com.beust.jcommander.Parameter;
import com.google.common.collect.ImmutableSet;
import google.registry.model.domain.token.AllocationToken;
import google.registry.persistence.VKey;
import java.util.List;

/** Shared base class for commands to update or delete allocation tokens. */
abstract class UpdateOrDeleteAllocationTokensCommand extends ConfirmingCommand
    implements CommandWithRemoteApi {

  @Parameter(
      names = {"-p", "--prefix"},
      description =
          "Act on all allocation tokens with this prefix, otherwise use '--tokens' to specify "
              + "exact tokens(s) to act on")
  protected String prefix;

  @Parameter(
      names = {"--tokens"},
      description =
          "Comma-separated list of tokens to act on; otherwise use '--prefix' to act on all tokens "
              + "with a given prefix")
  protected List<String> tokens;

  @Parameter(
      names = {"--dry_run"},
      description = "Do not actually update or delete the tokens; defaults to false")
  protected boolean dryRun;

  protected ImmutableSet<VKey<AllocationToken>> getTokenKeys() {
    checkArgument(
        tokens == null ^ prefix == null,
        "Must provide one of --tokens or --prefix, not both / neither");
    if (tokens != null) {
      ImmutableSet<VKey<AllocationToken>> keys =
          tokens.stream()
              .map(token -> VKey.create(AllocationToken.class, token))
              .collect(toImmutableSet());
      ImmutableSet<VKey<AllocationToken>> nonexistentKeys =
          transactIfJpaTm(
              () -> keys.stream().filter(key -> !tm().exists(key)).collect(toImmutableSet()));
      checkState(nonexistentKeys.isEmpty(), "Tokens with keys %s did not exist.", nonexistentKeys);
      return keys;
    } else {
      checkArgument(!prefix.isEmpty(), "Provided prefix should not be blank");
      return transactIfJpaTm(
          () ->
              tm().loadAll(AllocationToken.class).stream()
                  .filter(token -> token.getToken().startsWith(prefix))
                  .map(AllocationToken::createVKey)
                  .collect(toImmutableSet()));
    }
  }
}
