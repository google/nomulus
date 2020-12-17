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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.googlecode.objectify.Key;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.token.AllocationToken;
import google.registry.persistence.VKey;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Command to show allocation tokens. */
@Parameters(separators = " =", commandDescription = "Show allocation token(s)")
final class GetAllocationTokenCommand implements CommandWithRemoteApi {

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
      tm().load(tokenKeys).forEach((k, v) -> builder.put(k.getSqlKey().toString(), v));
    }
    ImmutableMap<String, AllocationToken> loadedTokens = builder.build();
    ImmutableMap<VKey<DomainBase>, DomainBase> domains = loadRedeemedDomains(loadedTokens.values());

    for (String token : mainParameters) {
      if (loadedTokens.containsKey(token)) {
        AllocationToken loadedToken = loadedTokens.get(token);
        System.out.println(loadedToken.toString());
        if (loadedToken.getRedemptionHistoryEntry().isEmpty()) {
          System.out.printf("Token %s was not redeemed.\n", token);
        } else {
          Key<DomainBase> domainOfyKey =
              loadedToken.getRedemptionHistoryEntry().get().getOfyKey().getParent();
          DomainBase domain =
              domains.get(VKey.create(DomainBase.class, domainOfyKey.getName(), domainOfyKey));
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
  private static ImmutableMap<VKey<DomainBase>, DomainBase> loadRedeemedDomains(
      Collection<AllocationToken> tokens) {
    ImmutableList<VKey<DomainBase>> domainKeys =
        tokens.stream()
            .map(AllocationToken::getRedemptionHistoryEntry)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(key -> tm().load(key))
            .map(he -> (Key<DomainBase>) he.getParent())
            .map(key -> VKey.create(DomainBase.class, key.getName(), key))
            .collect(toImmutableList());
    ImmutableMap.Builder<VKey<DomainBase>, DomainBase> domainsBuilder =
        new ImmutableMap.Builder<>();
    for (List<VKey<DomainBase>> keys : Lists.partition(domainKeys, BATCH_SIZE)) {
      tm().load(ImmutableList.copyOf(keys))
          .forEach((k, v) -> domainsBuilder.put((VKey<DomainBase>) k, v));
    }
    return domainsBuilder.build();
  }
}
