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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.model.tld.Registries.getTlds;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;

import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.model.tld.Registry;
import google.registry.request.Action;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import javax.inject.Inject;
import org.joda.time.DateTime;

/** An action that lists top-level domains, for use by the {@code nomulus list_tlds} command. */
@Action(
    service = Action.Service.TOOLS,
    path = ListTldsAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class ListTldsAction extends ListObjectsAction<Registry> {

  public static final String PATH = "/_dr/admin/list/tlds";

  @Inject Clock clock;
  @Inject ListTldsAction() {}

  @Override
  public ImmutableSet<String> getPrimaryKeyFields() {
    return ImmutableSet.of("tldStr");
  }

  @Override
  public ImmutableSet<Registry> loadObjects() {
    return getTlds().stream().map(Registry::get).collect(toImmutableSet());
  }

  @Override
  public ImmutableBiMap<String, String> getFieldAliases() {
    return ImmutableBiMap.of(
        "TLD", "tldStr",
        "dns", "dnsPaused",
        "escrow", "escrowEnabled");
  }

  @Override
  public ImmutableMap<String, String> getFieldOverrides(Registry registry) {
    final DateTime now = clock.nowUtc();
    return new ImmutableMap.Builder<String, String>()
        .put("dnsPaused", registry.getDnsPaused() ? "paused" : "-")
        .put("escrowEnabled", registry.getEscrowEnabled() ? "enabled" : "-")
        .put("tldState", registry.isPdt(now) ? "PDT" : registry.getTldState(now).toString())
        .put("tldStateTransitions", registry.getTldStateTransitions().toString())
        .put("renewBillingCost", registry.getStandardRenewCost(now).toString())
        .put("renewBillingCostTransitions", registry.getRenewBillingCostTransitions().toString())
        .build();
  }
}
