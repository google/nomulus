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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.model.tld.Tlds.assertTldsExist;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static google.registry.request.RequestParameters.PARAM_TLDS;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.EppResourceUtils;
import google.registry.model.domain.Domain;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import jakarta.inject.Inject;

/** An action that lists domains, for use by the {@code nomulus list_domains} command. */
@Action(
    service = Action.Service.BACKEND,
    path = ListDomainsAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_ADMIN)
public final class ListDomainsAction extends ListObjectsAction<Domain> {

  public static final String PATH = "/_dr/admin/list/domains";

  @Inject
  @Parameter(PARAM_TLDS)
  ImmutableSet<String> tlds;

  @Inject
  @Parameter("limit")
  int limit;

  @Inject Clock clock;

  @Inject
  ListDomainsAction() {}

  @Override
  public ImmutableSet<String> getPrimaryKeyFields() {
    return ImmutableSet.of("domainName");
  }

  @Override
  public ImmutableSet<Domain> loadObjects() {
    checkArgument(!tlds.isEmpty(), "Must specify TLDs to query");
    assertTldsExist(tlds);
    ImmutableList<Domain> domains = loadDomains();
    return ImmutableSet.copyOf(domains.reverse());
  }

  private ImmutableList<Domain> loadDomains() {
    return tm().transact(
            () ->
                tm().query(
                        "FROM Domain WHERE tld IN (:tlds) AND deletionTime > "
                            + "current_timestamp() ORDER BY creationTime DESC",
                        Domain.class)
                    .setParameter("tlds", tlds)
                    .setMaxResults(limit)
                    .getResultStream()
                    .map(EppResourceUtils.transformAtTime(tm().getTransactionTime()))
                    .collect(toImmutableList()));
  }
}
