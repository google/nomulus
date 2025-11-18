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
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableMap;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.console.User;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.auth.Auth;
import google.registry.security.XsrfTokenManager;
import jakarta.inject.Inject;
import jakarta.servlet.http.Cookie;
import org.json.JSONObject;

@Action(
    service = Service.CONSOLE,
    path = ConsoleUserDataAction.PATH,
    method = {GET},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleUserDataAction extends ConsoleApiAction {

  public static final String PATH = "/console-api/userdata";

  private final String productName;
  private final String supportPhoneNumber;
  private final String supportEmail;
  private final String technicalDocsUrl;

  @Inject
  public ConsoleUserDataAction(
      ConsoleApiParams consoleApiParams,
      @Config("productName") String productName,
      @Config("supportEmail") String supportEmail,
      @Config("supportPhoneNumber") String supportPhoneNumber,
      @Config("technicalDocsUrl") String technicalDocsUrl) {
    super(consoleApiParams);
    this.productName = productName;
    this.supportEmail = supportEmail;
    this.supportPhoneNumber = supportPhoneNumber;
    this.technicalDocsUrl = technicalDocsUrl;
  }

  @Override
  protected void getHandler(User user) {
    // As this is the first GET request, we use it as an opportunity to set a XSRF cookie
    // for angular to read - https://angular.io/guide/http-security-xsrf-protection
    Cookie xsrfCookie =
        new Cookie(
            XsrfTokenManager.X_CSRF_TOKEN,
            consoleApiParams.xsrfTokenManager().generateToken(user.getEmailAddress()));
    xsrfCookie.setSecure(true);
    consoleApiParams.response().addCookie(xsrfCookie);

    JSONObject json =
        new JSONObject(
            ImmutableMap.of(
                // Both isAdmin and globalRole flags are used by UI as an indicator to hide / show
                // specific set of widgets and screens.
                // For example:
                // - Admin sees everything and can create other users
                // - Empty global role indicates registrar user, with access to registrar specific
                // screens and widgets.
                // This however is merely for visual representation, as any back-end action contains
                // auth checks.
                "isAdmin", user.getUserRoles().isAdmin(),
                "globalRole", user.getUserRoles().getGlobalRole(),
                // registrar-specific roles
                "userRoles", user.getUserRoles().getRegistrarRoles(),
                // Include static contact resources in this call to minimize round trips
                "productName", productName,
                "supportEmail", supportEmail,
                "supportPhoneNumber", supportPhoneNumber,
                // Is used by UI to construct a link to registry resources
                "technicalDocsUrl", technicalDocsUrl));

    consoleApiParams.response().setPayload(json.toString());
    consoleApiParams.response().setStatus(SC_OK);
  }
}
