// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.EppResourceUtils.queryNotDeleted;
import static google.registry.model.registry.Registries.assertTldExists;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import google.registry.model.domain.DomainResource;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.util.Clock;
import java.util.Comparator;
import java.util.List;
import javax.inject.Inject;

/** An action that lists domains, for use by the {@code nomulus list_domains} command. */
@Action(path = ListDomainsAction.PATH, method = {GET, POST})
public final class ListDomainsAction extends ListObjectsAction<DomainResource> {

  /** An App Engine limitation on how many subqueries can be used in a single query. */
  private static final int MAX_NUM_SUBQUERIES = 30;
  private static final Comparator<DomainResource> COMPARATOR =
      new Comparator<DomainResource>() {
          @Override
          public int compare(DomainResource a, DomainResource b) {
            return a.getFullyQualifiedDomainName().compareTo(b.getFullyQualifiedDomainName());
          }};
  public static final String PATH = "/_dr/admin/list/domains";

  @Inject @Parameter("tlds") ImmutableSet<String> tlds;
  @Inject Clock clock;
  @Inject ListDomainsAction() {}

  @Override
  public ImmutableSet<String> getPrimaryKeyFields() {
    return ImmutableSet.of("fullyQualifiedDomainName");
  }

  @Override
  public ImmutableSet<DomainResource> loadObjects() {
    checkArgument(!tlds.isEmpty(), "Must specify TLDs to query");
    for (String tld : tlds) {
      assertTldExists(tld);
    }
    ImmutableSortedSet.Builder<DomainResource> builder =
        new ImmutableSortedSet.Builder<DomainResource>(COMPARATOR);
    for (List<String> batch : Lists.partition(tlds.asList(), MAX_NUM_SUBQUERIES)) {
      builder.addAll(queryNotDeleted(DomainResource.class, clock.nowUtc(), "tld in", batch));
    }
    return builder.build();
  }
}
