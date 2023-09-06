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
import com.google.common.collect.ImmutableMap;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.console.User;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.UserAuthInfo;
import google.registry.ui.server.registrar.JsonGetAction;
import javax.inject.Inject;
import org.json.JSONObject;

@Action(
    service = Action.Service.DEFAULT,
    path = ConsoleUserDataAction.PATH,
    method = {GET},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleUserDataAction implements JsonGetAction {

  public static final String PATH = "/console-api/userdata";

  private final AuthResult authResult;
  private final Response response;
  private final String technicalDocsUrl;

  @Inject
  public ConsoleUserDataAction(
      AuthResult authResult,
      Response response,
      @Config("technicalDocsUrl") String technicalDocsUrl) {
    this.response = response;
    this.authResult = authResult;
    this.technicalDocsUrl = technicalDocsUrl;
  }

  @Override
  public void run() {
    UserAuthInfo authInfo = authResult.userAuthInfo().get();
    if (!authInfo.consoleUser().isPresent()) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_UNAUTHORIZED);
      return;
    }
    User user = authInfo.consoleUser().get();

    JSONObject json =
        new JSONObject(
            ImmutableMap.of(
                // Both isAdmin and globalRole flags are used by UI as an indicator to hide / show
                // specific
                // set of widgets and screens.
                // For example:
                // - Admin sees everything and can create other users
                // - Empty global role indicates registrar user, with access to registrar specific
                // screens
                // and widgets
                // This however is merely for visual representation, as any back-end action contains
                // auth
                // checks
                "isAdmin", user.getUserRoles().isAdmin(),
                "globalRole", user.getUserRoles().getGlobalRole(),
                // Is used by UI to construct a link to registry resources
                "technicalDocsUrl", technicalDocsUrl));

    response.setPayload(json.toString());
    response.setStatus(HttpStatusCodes.STATUS_CODE_OK);
  }
}
