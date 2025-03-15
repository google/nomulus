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

package google.registry.tools.server;

import static com.google.common.collect.ImmutableSortedSet.toImmutableSortedSet;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;

import com.google.common.collect.ImmutableSet;
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.PremiumListDao;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.Optional;

/**
 * An action that lists premium lists, for use by the {@code nomulus list_premium_lists} command.
 */
@Action(
    service = GaeService.TOOLS,
    path = ListPremiumListsAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_ADMIN)
public final class ListPremiumListsAction extends ListObjectsAction<PremiumList> {

  public static final String PATH = "/_dr/admin/list/premiumLists";

  @Inject ListPremiumListsAction() {}

  @Override
  public ImmutableSet<String> getPrimaryKeyFields() {
    return ImmutableSet.of("name");
  }

  @Override
  public ImmutableSet<PremiumList> loadObjects() {
    return tm().transact(
            () ->
                tm().loadAllOf(PremiumList.class).stream()
                    .map(PremiumList::getName)
                    .map(PremiumListDao::getLatestRevision)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(toImmutableSortedSet(Comparator.comparing(PremiumList::getName))));
  }
}
