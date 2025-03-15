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
import static google.registry.persistence.transaction.BatchedQueries.loadAllOf;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static java.util.Comparator.comparing;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.EppResourceUtils;
import google.registry.model.host.Host;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import org.joda.time.DateTime;

/** An action that lists hosts, for use by the {@code nomulus list_hosts} command. */
@Action(
    service = GaeService.TOOLS,
    path = ListHostsAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_ADMIN)
public final class ListHostsAction extends ListObjectsAction<Host> {

  public static final String PATH = "/_dr/admin/list/hosts";

  @Inject Clock clock;
  @Inject ListHostsAction() {}

  @Override
  public ImmutableSet<String> getPrimaryKeyFields() {
    return ImmutableSet.of("hostName");
  }

  @Override
  public ImmutableSet<Host> loadObjects() {
    final DateTime now = clock.nowUtc();
    return loadAllOf(Host.class)
        .flatMap(ImmutableList::stream)
        .filter(host -> EppResourceUtils.isActive(host, now))
        .collect(toImmutableSortedSet(comparing(Host::getHostName)));
  }
}
