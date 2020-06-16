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
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static java.util.Comparator.comparing;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import google.registry.model.EppResourceUtils;
import google.registry.model.host.HostResource;
import google.registry.request.Action;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import javax.inject.Inject;
import org.joda.time.DateTime;

/** An action that lists hosts, for use by the {@code nomulus list_hosts} command. */
@Action(
    service = Action.Service.TOOLS,
    path = ListHostsAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class ListHostsAction extends ListObjectsAction<HostResource> {

  public static final String PATH = "/_dr/admin/list/hosts";

  @Inject Clock clock;
  @Inject ListHostsAction() {}

  @Override
  public ImmutableSet<String> getPrimaryKeyFields() {
    return ImmutableSet.of("fullyQualifiedHostName");
  }

  @Override
  public ImmutableSet<HostResource> loadObjects() {
    final DateTime now = clock.nowUtc();
    return Streams.stream(ofy().load().type(HostResource.class))
        .filter(host -> EppResourceUtils.isActive(host, now))
        .collect(toImmutableSortedSet(comparing(HostResource::getHostName)));
  }
}
