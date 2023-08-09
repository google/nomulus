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

package google.registry.ui.server.console.settings;

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.POST;

import com.google.api.client.http.HttpStatusCodes;
import com.google.gson.Gson;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.ui.server.registrar.JsonGetAction;
import java.util.Optional;
import javax.inject.Inject;

/**
 * Console action for editing fields on a registrar that are visible in WHOIS.
 *
 * <p>This doesn't cover many of the registrar fields but rather only those that are visible in
 * whois and don't have any other obvious means of edit.
 */
@Action(
    service = Action.Service.DEFAULT,
    path = WhoisRegistrarFieldsAction.PATH,
    method = {POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class WhoisRegistrarFieldsAction implements JsonGetAction {

  static final String PATH = "/console-api/settings/whois-fields";
  private final AuthResult authResult;
  private final Response response;
  private final Gson gson;
  private AuthenticatedRegistrarAccessor registrarAccessor;
  private Optional<Registrar> registrar;

  @Inject
  public WhoisRegistrarFieldsAction(
      AuthResult authResult,
      Response response,
      Gson gson,
      AuthenticatedRegistrarAccessor registrarAccessor,
      @Parameter("registrar") Optional<Registrar> registrar) {
    this.authResult = authResult;
    this.response = response;
    this.gson = gson;
    this.registrarAccessor = registrarAccessor;
    this.registrar = registrar;
  }

  @Override
  public void run() {
    if (!registrar.isPresent()) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
      response.setPayload(gson.toJson("'registrar' parameter is not present"));
      return;
    }

    User user = authResult.userAuthInfo().get().consoleUser().get();
    if (!user.getUserRoles()
        .hasPermission(
            registrar.get().getRegistrarId(), ConsolePermission.EDIT_REGISTRAR_DETAILS)) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_FORBIDDEN);
      return;
    }

    tm().transact(() -> loadAndModifyRegistrar(registrar.get()));
    response.setStatus(HttpStatusCodes.STATUS_CODE_OK);
  }

  private void loadAndModifyRegistrar(Registrar providedRegistrar) {
    Registrar savedRegistrar;
    try {
      // reload to make sure the object has all the correct fields
      savedRegistrar = registrarAccessor.getRegistrar(providedRegistrar.getRegistrarId());
    } catch (RegistrarAccessDeniedException e) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_FORBIDDEN);
      response.setPayload(e.getMessage());
      return;
    }

    Registrar.Builder newRegistrar = savedRegistrar.asBuilder();
    newRegistrar.setWhoisServer(providedRegistrar.getWhoisServer());
    newRegistrar.setUrl(providedRegistrar.getUrl());
    newRegistrar.setLocalizedAddress(providedRegistrar.getLocalizedAddress());
    tm().put(newRegistrar.build());
  }
}
