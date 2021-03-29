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
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.registry.Registries.assertTldsExist;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static google.registry.request.RequestParameters.PARAM_TLDS;
import static java.util.Comparator.comparing;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import google.registry.model.EppResource;
import google.registry.model.EppResourceUtils;
import google.registry.model.domain.DomainBase;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.util.NonFinalForTesting;
import java.util.List;
import javax.inject.Inject;
import org.joda.time.DateTime;

/** An action that lists domains, for use by the {@code nomulus list_domains} command. */
@Action(
    service = Action.Service.TOOLS,
    path = ListDomainsAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class ListDomainsAction extends ListObjectsAction<DomainBase> {

  /** An App Engine limitation on how many subqueries can be used in a single query. */
  @VisibleForTesting @NonFinalForTesting static int maxNumSubqueries = 30;

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
    return ImmutableSet.of("fullyQualifiedDomainName");
  }

  @Override
  public ImmutableSet<DomainBase> loadObjects() {
    checkArgument(!tlds.isEmpty(), "Must specify TLDs to query");
    assertTldsExist(tlds);
    ImmutableList<DomainBase> domains = tm().isOfy() ? loadDomainsOfy() : loadDomainsSql();
    return ImmutableSet.copyOf(domains.reverse());
  }

  private ImmutableList<DomainBase> loadDomainsOfy() {
    DateTime now = clock.nowUtc();
    ImmutableList.Builder<DomainBase> domainsBuilder = new ImmutableList.Builder<>();
    // Combine the batches together by sorting all domains together with newest first, applying the
    // limit, and then reversing for display order.
    for (List<String> tldsBatch : Lists.partition(tlds.asList(), maxNumSubqueries)) {
      ofy()
          .load()
          .type(DomainBase.class)
          .filter("tld in", tldsBatch)
          // Get the N most recently created domains (requires ordering in descending order).
          .order("-creationTime")
          .limit(limit)
          .list()
          .stream()
          .map(EppResourceUtils.transformAtTime(now))
          // Deleted entities must be filtered out post-query because queries don't allow
          // ordering with two filters.
          .filter(d -> d.getDeletionTime().isAfter(now))
          .forEach(domainsBuilder::add);
    }
    return domainsBuilder.build().stream()
        .sorted(comparing(EppResource::getCreationTime).reversed())
        .limit(limit)
        .collect(toImmutableList());
  }

  private ImmutableList<DomainBase> loadDomainsSql() {
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                    .query(
                        "FROM Domain WHERE tld IN (:tlds) AND deletionTime > "
                            + "current_timestamp() ORDER BY creationTime DESC",
                        DomainBase.class)
                    .setParameter("tlds", tlds)
                    .setMaxResults(limit)
                    .getResultStream()
                    .map(EppResourceUtils.transformAtTime(jpaTm().getTransactionTime()))
                    .collect(toImmutableList()));
  }
}
