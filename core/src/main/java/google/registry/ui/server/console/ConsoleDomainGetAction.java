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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.ui.server.registrar.RegistrarConsoleModule.PARAM_DOMAIN;

import com.google.api.client.http.HttpStatusCodes;
import com.google.gson.Gson;
import google.registry.model.EppResourceUtils;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.model.domain.Domain;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.UserAuthInfo;
import google.registry.ui.server.registrar.JsonGetAction;
import java.util.Optional;
import javax.inject.Inject;

/** Returns a JSON representation of a domain to the registrar console. */
@Action(
    service = Action.Service.DEFAULT,
    path = ConsoleDomainGetAction.PATH,
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleDomainGetAction implements JsonGetAction {

  public static final String PATH = "/console-api/domain";

  private final AuthResult authResult;
  private final Response response;
  private final Gson gson;
  private final String paramDomain;

  @Inject
  public ConsoleDomainGetAction(
      AuthResult authResult,
      Response response,
      Gson gson,
      @Parameter(PARAM_DOMAIN) String paramDomain) {
    this.authResult = authResult;
    this.response = response;
    this.gson = gson;
    this.paramDomain = paramDomain;
  }

  @Override
  public void run() {
    if (!authResult.isAuthenticated() || authResult.userAuthInfo().isEmpty()) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_UNAUTHORIZED);
      return;
    }
    UserAuthInfo authInfo = authResult.userAuthInfo().get();
    if (authInfo.consoleUser().isEmpty()) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_UNAUTHORIZED);
      return;
    }
    User user = authInfo.consoleUser().get();
    Optional<Domain> possibleDomain =
        tm().transact(
                () ->
                    EppResourceUtils.loadByForeignKeyCached(
                        Domain.class, paramDomain, tm().getTransactionTime()));
    if (possibleDomain.isEmpty()) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_NOT_FOUND);
      return;
    }
    Domain domain = possibleDomain.get();
    if (!user.getUserRoles()
        .hasPermission(domain.getCurrentSponsorRegistrarId(), ConsolePermission.DOWNLOAD_DOMAINS)) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_NOT_FOUND);
      return;
    }
    response.setStatus(HttpStatusCodes.STATUS_CODE_OK);
    response.setPayload(gson.toJson(domain));
  }
}
