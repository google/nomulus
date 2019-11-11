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

import static google.registry.request.auth.AuthenticatedRegistrarAccessor.Role.ADMIN;
import static google.registry.request.auth.AuthenticatedRegistrarAccessor.Role.OWNER;
import static google.registry.ui.server.SoyTemplateUtils.CSS_RENAMING_MAP_SUPPLIER;
import static google.registry.ui.server.registrar.RegistrarConsoleModule.PARAM_CLIENT_ID;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.flogger.FluentLogger;
import com.google.template.soy.data.SoyMapData;
import com.google.template.soy.tofu.SoyTofu;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryEnvironment;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.Role;
import google.registry.ui.server.SoyTemplateUtils;
import google.registry.ui.soy.registrar.ConsoleSoyInfo;
import java.util.HashMap;
import java.util.Optional;
import javax.inject.Inject;

/** Action that serves Registrar Console single HTML page (SPA). */
@Action(service = Action.Service.DEFAULT, path = ConsoleUiAction.PATH, auth = Auth.AUTH_PUBLIC)
public final class ConsoleUiAction extends HtmlAction {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String PATH = "/registrar";

  private static final Supplier<SoyTofu> TOFU_SUPPLIER =
      SoyTemplateUtils.createTofuSupplier(
          google.registry.ui.soy.ConsoleSoyInfo.getInstance(),
          google.registry.ui.soy.registrar.ConsoleSoyInfo.getInstance(),
          google.registry.ui.soy.AnalyticsSoyInfo.getInstance());

  @Inject RegistrarConsoleMetrics registrarConsoleMetrics;
  @Inject AuthenticatedRegistrarAccessor registrarAccessor;

  @Inject
  @Config("integrationEmail")
  String integrationEmail;

  @Inject
  @Config("supportEmail")
  String supportEmail;

  @Inject
  @Config("announcementsEmail")
  String announcementsEmail;

  @Inject
  @Config("supportPhoneNumber")
  String supportPhoneNumber;

  @Inject
  @Config("technicalDocsUrl")
  String technicalDocsUrl;

  @Inject
  @Config("registrarConsoleEnabled")
  boolean enabled;

  @Inject
  @Parameter(PARAM_CLIENT_ID)
  Optional<String> paramClientId;

  @Inject
  ConsoleUiAction() {}

  @Override
  public void runAfterLogin(HashMap<String, Object> data) {
    SoyMapData soyMapData = new SoyMapData();
    data.forEach((key, value) -> soyMapData.put(key, value));

    soyMapData.put("integrationEmail", integrationEmail);
    soyMapData.put("supportEmail", supportEmail);
    soyMapData.put("announcementsEmail", announcementsEmail);
    soyMapData.put("supportPhoneNumber", supportPhoneNumber);
    soyMapData.put("technicalDocsUrl", technicalDocsUrl);
    if (!enabled) {
      response.setStatus(SC_SERVICE_UNAVAILABLE);
      response.setPayload(
          TOFU_SUPPLIER
              .get()
              .newRenderer(ConsoleSoyInfo.DISABLED)
              .setCssRenamingMap(CSS_RENAMING_MAP_SUPPLIER.get())
              .setData(soyMapData)
              .render());
      return;
    }
    ImmutableSetMultimap<String, Role> roleMap = registrarAccessor.getAllClientIdWithRoles();
    soyMapData.put("allClientIds", roleMap.keySet());
    soyMapData.put("environment", RegistryEnvironment.get().toString());
    // We set the initial value to the value that will show if guessClientId throws.
    String clientId = "<null>";
    try {
      clientId = paramClientId.orElse(registrarAccessor.guessClientId());
      soyMapData.put("clientId", clientId);
      soyMapData.put("isOwner", roleMap.containsEntry(clientId, OWNER));
      soyMapData.put("isAdmin", roleMap.containsEntry(clientId, ADMIN));

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
          TOFU_SUPPLIER
              .get()
              .newRenderer(ConsoleSoyInfo.WHOAREYOU)
              .setCssRenamingMap(CSS_RENAMING_MAP_SUPPLIER.get())
              .setData(soyMapData)
              .render());
      registrarConsoleMetrics.registerConsoleRequest(
          clientId, paramClientId.isPresent(), roleMap.get(clientId), "FORBIDDEN");
      return;
    } catch (Exception e) {
      registrarConsoleMetrics.registerConsoleRequest(
          clientId, paramClientId.isPresent(), roleMap.get(clientId), "UNEXPECTED ERROR");
      throw e;
    }

    String payload =
        TOFU_SUPPLIER
            .get()
            .newRenderer(ConsoleSoyInfo.MAIN)
            .setCssRenamingMap(CSS_RENAMING_MAP_SUPPLIER.get())
            .setData(soyMapData)
            .render();
    response.setPayload(payload);
    registrarConsoleMetrics.registerConsoleRequest(
        clientId, paramClientId.isPresent(), roleMap.get(clientId), "SUCCESS");
  }

  @Override
  public String getPath() {
    return PATH;
  }
}
