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

package google.registry.tools;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.token.AllocationToken;
import google.registry.persistence.VKey;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Command to show allocation tokens. */
@Parameters(separators = " =", commandDescription = "Show allocation token(s)")
final class GetAllocationTokenCommand implements Command {

  @Parameter(
      description = "Allocation token(s)",
      required = true)
  private List<String> mainParameters;

  private static final int BATCH_SIZE = 20;

  @Override
  public void run() {
    ImmutableMap.Builder<String, AllocationToken> builder = new ImmutableMap.Builder<>();
    for (List<String> tokens : Lists.partition(mainParameters, BATCH_SIZE)) {
      ImmutableList<VKey<AllocationToken>> tokenKeys =
          tokens.stream()
              .map(t -> VKey.create(AllocationToken.class, t))
              .collect(toImmutableList());
      tm().transact(
              () ->
                  tm().loadByKeysIfPresent(tokenKeys)
                      .forEach((k, v) -> builder.put(k.getKey().toString(), v)));
    }
    ImmutableMap<String, AllocationToken> loadedTokens = builder.build();
    ImmutableMap<VKey<Domain>, Domain> domains =
        tm().transact(() -> loadRedeemedDomains(loadedTokens.values()));

    for (String token : mainParameters) {
      if (loadedTokens.containsKey(token)) {
        AllocationToken loadedToken = loadedTokens.get(token);
        System.out.println(loadedToken.toString());
        if (loadedToken.getRedemptionHistoryId().isEmpty()) {
          System.out.printf("Token %s was not redeemed.\n", token);
        } else {
          VKey<Domain> domainKey =
              VKey.create(Domain.class, loadedToken.getRedemptionHistoryId().get().getRepoId());
          Domain domain = domains.get(domainKey);
          if (domain == null) {
            System.out.printf("ERROR: Token %s was redeemed but domain can't be loaded.\n", token);
          } else {
            System.out.printf(
                "Token %s was redeemed to create domain %s at %s.\n",
                token, domain.getDomainName(), domain.getCreationTime());
          }
        }
      } else {
        System.out.printf("ERROR: Token %s does not exist.\n", token);
      }
      System.out.println();
    }
  }

  @SuppressWarnings("unchecked")
  private static ImmutableMap<VKey<Domain>, Domain> loadRedeemedDomains(
      Collection<AllocationToken> tokens) {
    ImmutableList<VKey<Domain>> domainKeys =
        tokens.stream()
            .map(AllocationToken::getRedemptionHistoryId)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(hi -> tm().loadByKey(VKey.create(DomainHistory.class, hi)))
            .map(dh -> VKey.create(Domain.class, dh.getRepoId()))
            .collect(toImmutableList());
    ImmutableMap.Builder<VKey<Domain>, Domain> domainsBuilder = new ImmutableMap.Builder<>();
    for (List<VKey<Domain>> keys : Lists.partition(domainKeys, BATCH_SIZE)) {
      tm().loadByKeys(ImmutableList.copyOf(keys))
          .forEach((k, v) -> domainsBuilder.put((VKey<Domain>) k, v));
    }
    return domainsBuilder.build();
  }
}
