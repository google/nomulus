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

import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;

import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;

/** An action that lists registrars, for use by the {@code nomulus list_registrars} command. */
@Action(
    service = Action.Service.BACKEND,
    path = ListRegistrarsAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_ADMIN)
public final class ListRegistrarsAction extends ListObjectsAction<Registrar> {

  public static final String PATH = "/_dr/admin/list/registrars";

  @Inject ListRegistrarsAction() {}

  @Override
  public ImmutableSet<String> getPrimaryKeyFields() {
    return ImmutableSet.of("registrarId");
  }

  @Override
  public ImmutableSet<Registrar> loadObjects() {
    return ImmutableSet.copyOf(Registrar.loadAll());
  }

  @Override
  public ImmutableBiMap<String, String> getFieldAliases() {
    return ImmutableBiMap.of(
        "billingId", "billingIdentifier",
        "clientId", "registrarId",
        "premiumNames", "blockPremiumNames");
  }

  @Override
  public ImmutableMap<String, String> getFieldOverrides(Registrar registrar) {
    return ImmutableMap.of(
        "blockPremiumNames", registrar.getBlockPremiumNames() ? "blocked" : "-");
  }
}
