// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.console;

import static google.registry.request.Action.Method.GET;

import com.google.api.client.http.HttpStatusCodes;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.google.gson.Gson;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthResult;
import google.registry.ui.server.registrar.JsonGetAction;
import javax.inject.Inject;

@Action(
    service = Action.Service.DEFAULT,
    path = RegistrarsAction.PATH,
    method = {GET},
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class RegistrarsAction implements JsonGetAction {
  static final String PATH = "/console-api/registrars";

  private final AuthResult authResult;
  private final Response response;
  private final Gson gson;

  @Inject
  public RegistrarsAction(AuthResult authResult, Response response, Gson gson) {
    this.authResult = authResult;
    this.response = response;
    this.gson = gson;
  }

  @Override
  public void run() {
    User user = authResult.userAuthInfo().get().consoleUser().get();
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.MANAGE_REGISTRARS)) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_FORBIDDEN);
      return;
    }
    ImmutableList<String> registrarIds =
        Streams.stream(Registrar.loadAllCached())
            .filter(r -> r.isLive() && r.getType() == Registrar.Type.REAL)
            .map(Registrar::getRegistrarId)
            .collect(ImmutableList.toImmutableList());

    response.setPayload(gson.toJson(registrarIds));
    response.setStatus(HttpStatusCodes.STATUS_CODE_OK);
  }
}
