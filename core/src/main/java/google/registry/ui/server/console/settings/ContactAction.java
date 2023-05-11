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
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;

import com.google.api.client.http.HttpStatusCodes;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.persistence.transaction.QueryComposer.Comparator;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthResult;
import google.registry.ui.server.console.ConsoleActionBase;
import google.registry.ui.server.registrar.RegistrarSettingsAction;
import java.util.Collections;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;

@Action(
    service = Action.Service.DEFAULT,
    path = ContactAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ContactAction extends ConsoleActionBase {
  static final String PATH = "/console-api/settings/contacts/";
  private final HttpServletRequest req;
  private final AuthResult authResult;
  private final Response response;
  private final Gson gson;
  private final Optional<ImmutableSet<RegistrarPoc>> contacts;
  private final String registrarId;

  @Inject
  public ContactAction(
      HttpServletRequest req,
      AuthResult authResult,
      Response response,
      Gson gson,
      @Parameter("registrarId") String registrarId,
      @Parameter("contacts") Optional<ImmutableSet<RegistrarPoc>> contacts) {
    this.authResult = authResult;
    this.response = response;
    this.gson = gson;
    this.registrarId = registrarId;
    this.contacts = contacts;
    this.req = req;
  }

  @Override
  public void run() {
    boolean isGet = this.req.getMethod().equals(GET.toString());

    if (!verifyUserPermissions(isGet, registrarId, authResult, response)) {
      return;
    }

    if (isGet) {
      getHandler();
    } else {
      postHandler();
    }
  }

  private void getHandler() {
    ImmutableList<RegistrarPoc> am =
        tm().transact(
                () ->
                    tm().createQueryComposer(RegistrarPoc.class)
                        .where("registrarId", Comparator.EQ, registrarId)
                        .list());

    response.setStatus(HttpStatusCodes.STATUS_CODE_OK);
    response.setPayload(gson.toJson(am));
  }

  private void postHandler() {
    if (!contacts.isPresent()) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
      response.setPayload(gson.toJson("Contacts parameter is not present"));
      return;
    }

    Registrar registrar =
        Registrar.loadByRegistrarId(registrarId)
            .orElseThrow(
                () ->
                    new IllegalStateException(String.format("Unknown registrar %s", registrarId)));

    ImmutableSet<RegistrarPoc> oldContacts = registrar.getContacts();
    // TODO: @ptkach - refactor out contacts update functionality after RegistrarSettingsAction is
    // deprecated
    ImmutableSet<RegistrarPoc> updatedContacts =
        RegistrarSettingsAction.readContacts(
            registrar,
            oldContacts,
            Collections.singletonMap(
                "contacts",
                contacts.get().stream().map(c -> c.toJsonMap()).collect(Collectors.toList())));
    RegistrarSettingsAction.checkContactRequirements(oldContacts, updatedContacts);
    RegistrarPoc.updateContacts(registrar, updatedContacts);
    response.setStatus(HttpStatusCodes.STATUS_CODE_OK);
  }
}
