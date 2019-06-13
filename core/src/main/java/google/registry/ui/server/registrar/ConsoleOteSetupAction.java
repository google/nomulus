// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.net.HttpHeaders.LOCATION;
import static com.google.common.net.HttpHeaders.X_FRAME_OPTIONS;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_MOVED_TEMPORARILY;

import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserService;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.Resources;
import com.google.common.net.MediaType;
import com.google.template.soy.shared.SoyCssRenamingMap;
import com.google.template.soy.tofu.SoyTofu;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryEnvironment;
import google.registry.model.OteAccountBuilder;
import google.registry.request.Action;
import google.registry.request.Action.Method;
import google.registry.request.Parameter;
import google.registry.request.RequestMethod;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.security.XsrfTokenManager;
import google.registry.ui.server.SendEmailUtils;
import google.registry.ui.server.SoyTemplateUtils;
import google.registry.ui.soy.registrar.OteSetupConsoleSoyInfo;
import google.registry.util.StringGenerator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;

/**
 * Action that serves OT&amp;E setup web page.
 *
 * <p>This Action does 2 things: - for GET, just returns the form that asks for the clientId and
 * email. - for POST, receives the clientId and email and generates the OTE entities.
 *
 * <p>TODO(b/120201577): once we can have 2 different Actions with the same path (different
 * Methods), separate this class to 2 Actions.
 */
@Action(
    service = Action.Service.DEFAULT,
    path = ConsoleOteSetupAction.PATH,
    method = {Method.POST, Method.GET},
    auth = Auth.AUTH_PUBLIC)
public final class ConsoleOteSetupAction implements Runnable {

  private static final int PASSWORD_LENGTH = 16;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String PATH = "/registrar-ote-setup";

  private static final Supplier<SoyTofu> TOFU_SUPPLIER =
      SoyTemplateUtils.createTofuSupplier(
          google.registry.ui.soy.ConsoleSoyInfo.getInstance(),
          google.registry.ui.soy.FormsSoyInfo.getInstance(),
          google.registry.ui.soy.AnalyticsSoyInfo.getInstance(),
          google.registry.ui.soy.registrar.OteSetupConsoleSoyInfo.getInstance());

  @VisibleForTesting  // webdriver and screenshot tests need this
  public static final Supplier<SoyCssRenamingMap> CSS_RENAMING_MAP_SUPPLIER =
      SoyTemplateUtils.createCssRenamingMapSupplier(
          Resources.getResource("google/registry/ui/css/registrar_bin.css.js"),
          Resources.getResource("google/registry/ui/css/registrar_dbg.css.js"));

  @Inject HttpServletRequest req;
  @Inject @RequestMethod Method method;
  @Inject Response response;
  @Inject AuthenticatedRegistrarAccessor registrarAccessor;
  @Inject UserService userService;
  @Inject XsrfTokenManager xsrfTokenManager;
  @Inject AuthResult authResult;
  @Inject RegistryEnvironment registryEnvironment;
  @Inject SendEmailUtils sendEmailUtils;
  @Inject @Config("logoFilename") String logoFilename;
  @Inject @Config("productName") String productName;
  @Inject @Config("analyticsConfig") Map<String, Object> analyticsConfig;
  @Inject @Named("base58StringGenerator") StringGenerator passwordGenerator;
  @Inject @Parameter("clientId") Optional<String> clientId;
  @Inject @Parameter("email") Optional<String> email;
  @Inject @Parameter("password") Optional<String> optionalPassword;

  @Inject ConsoleOteSetupAction() {}

  @Override
  public void run() {
    response.setHeader(X_FRAME_OPTIONS, "SAMEORIGIN");  // Disallow iframing.
    response.setHeader("X-Ui-Compatible", "IE=edge");  // Ask IE not to be silly.

    logger.atInfo().log(
        "User %s is accessing the OT&E setup page. Method= %s",
        registrarAccessor.userIdForLogging(), method);
    checkState(registryEnvironment != RegistryEnvironment.PRODUCTION, "Can't create OT&E in prod");
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

    // Using HashMap to allow null values
    HashMap<String, Object> data = new HashMap<>();
    data.put("logoFilename", logoFilename);
    data.put("productName", productName);
    data.put("username", user.getNickname());
    data.put("logoutUrl", userService.createLogoutURL(PATH));
    data.put("xsrfToken", xsrfTokenManager.generateToken(user.getEmail()));
    data.put("analyticsConfig", analyticsConfig);
    response.setContentType(MediaType.HTML_UTF_8);

    if (!registrarAccessor.isAdmin()) {
      response.setStatus(SC_FORBIDDEN);
      response.setPayload(
          TOFU_SUPPLIER
              .get()
              .newRenderer(OteSetupConsoleSoyInfo.WHOAREYOU)
              .setCssRenamingMap(CSS_RENAMING_MAP_SUPPLIER.get())
              .setData(data)
              .render());
      return;
    }
    switch (method) {
      case POST:
        runPost(data);
        return;
      case GET:
        runGet(data);
        return;
      default:
        return;
    }
  }

  private void runPost(HashMap<String, Object> data) {
    try {
      checkState(clientId.isPresent() && email.isPresent(), "Must supply clientId and email");

      data.put("baseClientId", clientId.get());
      data.put("contactEmail", email.get());

      String password = optionalPassword.orElse(passwordGenerator.createString(PASSWORD_LENGTH));
      ImmutableMap<String, String> clientIdToTld =
          OteAccountBuilder.forClientId(clientId.get())
              .addContact(email.get())
              .setPassword(password)
              .buildAndPersist();

      sendExternalUpdates(clientIdToTld);

      data.put("clientIdToTld", clientIdToTld);
      data.put("password", password);

      response.setPayload(
          TOFU_SUPPLIER
              .get()
              .newRenderer(OteSetupConsoleSoyInfo.RESULT_SUCCESS)
              .setCssRenamingMap(CSS_RENAMING_MAP_SUPPLIER.get())
              .setData(data)
              .render());
    } catch (Throwable e) {
      logger.atWarning().withCause(e).log(
          "Failed to setup OT&E. clientId: %s, email: %s", clientId.get(), email.get());
      data.put("errorMessage", e.getMessage());
      response.setPayload(
          TOFU_SUPPLIER
          .get()
          .newRenderer(OteSetupConsoleSoyInfo.FORM_PAGE)
          .setCssRenamingMap(CSS_RENAMING_MAP_SUPPLIER.get())
          .setData(data)
          .render());
    }
  }

  private void runGet(HashMap<String, Object> data) {
    // set the values to pre-fill, if given
    data.put("baseClientId", clientId.orElse(null));
    data.put("contactEmail", email.orElse(null));

    response.setPayload(
        TOFU_SUPPLIER
            .get()
            .newRenderer(OteSetupConsoleSoyInfo.FORM_PAGE)
            .setCssRenamingMap(CSS_RENAMING_MAP_SUPPLIER.get())
            .setData(data)
            .render());
  }


  private void sendExternalUpdates(ImmutableMap<String, String> clientIdToTld) {
    if (!sendEmailUtils.hasRecipients()) {
      return;
    }
    String environment = Ascii.toLowerCase(String.valueOf(registryEnvironment));
    StringBuilder builder = new StringBuilder();
    builder.append(
        String.format(
            "The following entities were created in %s by %s:\n",
            environment, registrarAccessor.userIdForLogging()));
    clientIdToTld.forEach(
        (clientId, tld) ->
            builder.append(
                String.format("   Registrar %s with access to TLD %s\n", clientId, tld)));
    builder.append(String.format("Gave user %s web access to these Registrars\n", email.get()));
    sendEmailUtils.sendEmail(
        String.format(
            "OT&E for registrar %s created in %s",
            clientId.get(),
            environment),
        builder.toString());
  }
}
