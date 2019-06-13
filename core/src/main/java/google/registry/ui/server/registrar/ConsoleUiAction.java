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

package google.registry.ui.server.registrar;

import static com.google.common.net.HttpHeaders.LOCATION;
import static com.google.common.net.HttpHeaders.X_FRAME_OPTIONS;
import static google.registry.request.auth.AuthenticatedRegistrarAccessor.Role.ADMIN;
import static google.registry.request.auth.AuthenticatedRegistrarAccessor.Role.OWNER;
import static google.registry.ui.server.registrar.RegistrarConsoleModule.PARAM_CLIENT_ID;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_MOVED_TEMPORARILY;
import static javax.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE;

import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserService;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.Resources;
import com.google.common.net.MediaType;
import com.google.template.soy.data.SoyMapData;
import com.google.template.soy.shared.SoyCssRenamingMap;
import com.google.template.soy.tofu.SoyTofu;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryEnvironment;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.Role;
import google.registry.security.XsrfTokenManager;
import google.registry.ui.server.SoyTemplateUtils;
import google.registry.ui.soy.registrar.ConsoleSoyInfo;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;

/** Action that serves Registrar Console single HTML page (SPA). */
@Action(service = Action.Service.DEFAULT, path = ConsoleUiAction.PATH, auth = Auth.AUTH_PUBLIC)
public final class ConsoleUiAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String PATH = "/registrar";

  private static final Supplier<SoyTofu> TOFU_SUPPLIER =
      SoyTemplateUtils.createTofuSupplier(
          google.registry.ui.soy.ConsoleSoyInfo.getInstance(),
          google.registry.ui.soy.registrar.ConsoleSoyInfo.getInstance(),
          google.registry.ui.soy.AnalyticsSoyInfo.getInstance());

  @VisibleForTesting  // webdriver and screenshot tests need this
  public static final Supplier<SoyCssRenamingMap> CSS_RENAMING_MAP_SUPPLIER =
      SoyTemplateUtils.createCssRenamingMapSupplier(
          Resources.getResource("google/registry/ui/css/registrar_bin.css.js"),
          Resources.getResource("google/registry/ui/css/registrar_dbg.css.js"));

  @Inject HttpServletRequest req;
  @Inject Response response;
  @Inject RegistrarConsoleMetrics registrarConsoleMetrics;
  @Inject AuthenticatedRegistrarAccessor registrarAccessor;
  @Inject UserService userService;
  @Inject XsrfTokenManager xsrfTokenManager;
  @Inject AuthResult authResult;
  @Inject RegistryEnvironment environment;
  @Inject @Config("logoFilename") String logoFilename;
  @Inject @Config("productName") String productName;
  @Inject @Config("integrationEmail") String integrationEmail;
  @Inject @Config("supportEmail") String supportEmail;
  @Inject @Config("announcementsEmail") String announcementsEmail;
  @Inject @Config("supportPhoneNumber") String supportPhoneNumber;
  @Inject @Config("technicalDocsUrl") String technicalDocsUrl;
  @Inject @Config("registrarConsoleEnabled") boolean enabled;
  @Inject @Config("analyticsConfig") Map<String, Object> analyticsConfig;
  @Inject @Parameter(PARAM_CLIENT_ID) Optional<String> paramClientId;
  @Inject ConsoleUiAction() {}

  @Override
  public void run() {
    if (!authResult.userAuthInfo().isPresent()) {
      response.setStatus(SC_MOVED_TEMPORARILY);
      String location;
      try {
        location = userService.createLoginURL(req.getRequestURI());
      } catch (IllegalArgumentException e) {
        // UserServiceImpl.createLoginURL() throws IllegalArgumentException if underlying API call
        // returns an error code of NOT_ALLOWED. createLoginURL() assumes that the error is caused
        // by an invalid URL. But in fact, the error can also occur if UserService doesn't have any
        // user information, which happens when the request has been authenticated as internal. In
        // this case, we want to avoid dying before we can send the redirect, so just redirect to
        // the root path.
        location = "/";
      }
      response.setHeader(LOCATION, location);
      return;
    }
    User user = authResult.userAuthInfo().get().user();
    response.setContentType(MediaType.HTML_UTF_8);
    response.setHeader(X_FRAME_OPTIONS, "SAMEORIGIN");  // Disallow iframing.
    response.setHeader("X-Ui-Compatible", "IE=edge");  // Ask IE not to be silly.
    SoyMapData data = new SoyMapData();
    data.put("logoFilename", logoFilename);
    data.put("productName", productName);
    data.put("integrationEmail", integrationEmail);
    data.put("supportEmail", supportEmail);
    data.put("announcementsEmail", announcementsEmail);
    data.put("supportPhoneNumber", supportPhoneNumber);
    data.put("technicalDocsUrl", technicalDocsUrl);
    data.put("analyticsConfig", analyticsConfig);
    if (!enabled) {
      response.setStatus(SC_SERVICE_UNAVAILABLE);
      response.setPayload(
          TOFU_SUPPLIER.get()
              .newRenderer(ConsoleSoyInfo.DISABLED)
              .setCssRenamingMap(CSS_RENAMING_MAP_SUPPLIER.get())
              .setData(data)
              .render());
      return;
    }
    data.put("username", user.getNickname());
    data.put("logoutUrl", userService.createLogoutURL(PATH));
    data.put("xsrfToken", xsrfTokenManager.generateToken(user.getEmail()));
    ImmutableSetMultimap<String, Role> roleMap = registrarAccessor.getAllClientIdWithRoles();
    data.put("allClientIds", roleMap.keySet());
    data.put("environment", environment.toString());
    // We set the initial value to the value that will show if guessClientId throws.
    String clientId = "<null>";
    try {
      clientId = paramClientId.orElse(registrarAccessor.guessClientId());
      data.put("clientId", clientId);
      data.put("isOwner", roleMap.containsEntry(clientId, OWNER));
      data.put("isAdmin", roleMap.containsEntry(clientId, ADMIN));

      // We want to load the registrar even if we won't use it later (even if we remove the
      // requireFeeExtension) - to make sure the user indeed has access to the guessed registrar.
      //
      // Note that not doing so (and just passing the "clientId" as given) isn't a security issue
      // since we double check the access to the registrar on any read / update request. We have to
      // - since the access might get revoked between the initial page load and the request! (also
      // because the requests come from the browser, and can easily be faked)
      registrarAccessor.getRegistrar(clientId);
    } catch (RegistrarAccessDeniedException e) {
      logger.atWarning().withCause(e).log(
          "User %s doesn't have access to registrar console.", authResult.userIdForLogging());
      response.setStatus(SC_FORBIDDEN);
      response.setPayload(
          TOFU_SUPPLIER.get()
              .newRenderer(ConsoleSoyInfo.WHOAREYOU)
              .setCssRenamingMap(CSS_RENAMING_MAP_SUPPLIER.get())
              .setData(data)
              .render());
      registrarConsoleMetrics.registerConsoleRequest(
          clientId, paramClientId.isPresent(), roleMap.get(clientId), "FORBIDDEN");
      return;
    } catch (Exception e) {
      registrarConsoleMetrics.registerConsoleRequest(
          clientId, paramClientId.isPresent(), roleMap.get(clientId), "UNEXPECTED ERROR");
      throw e;
    }

    String payload = TOFU_SUPPLIER.get()
            .newRenderer(ConsoleSoyInfo.MAIN)
            .setCssRenamingMap(CSS_RENAMING_MAP_SUPPLIER.get())
            .setData(data)
            .render();
    response.setPayload(payload);
    registrarConsoleMetrics.registerConsoleRequest(
        clientId, paramClientId.isPresent(), roleMap.get(clientId), "SUCCESS");
  }
}
