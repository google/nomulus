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
import static google.registry.config.RegistryEnvironment.PRODUCTION;
import static google.registry.ui.server.SoyTemplateUtils.CSS_RENAMING_MAP_SUPPLIER;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;

import com.google.common.base.Ascii;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.template.soy.tofu.SoyTofu;
import google.registry.config.RegistryEnvironment;
import google.registry.model.OteAccountBuilder;
import google.registry.request.Action;
import google.registry.request.Action.Method;
import google.registry.request.Parameter;
import google.registry.request.RequestMethod;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.ui.server.SendEmailUtils;
import google.registry.ui.server.SoyTemplateUtils;
import google.registry.ui.soy.registrar.OteSetupConsoleSoyInfo;
import google.registry.util.StringGenerator;
import java.util.HashMap;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Named;

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
public final class ConsoleOteSetupAction extends ConsoleAction {

  public static final String PATH = "/registrar-ote-setup";
  private static final int PASSWORD_LENGTH = 16;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Supplier<SoyTofu> TOFU_SUPPLIER =
      SoyTemplateUtils.createTofuSupplier(
          google.registry.ui.soy.ConsoleSoyInfo.getInstance(),
          google.registry.ui.soy.FormsSoyInfo.getInstance(),
          google.registry.ui.soy.AnalyticsSoyInfo.getInstance(),
          google.registry.ui.soy.registrar.OteSetupConsoleSoyInfo.getInstance());

  @Inject @RequestMethod Method method;
  @Inject AuthenticatedRegistrarAccessor registrarAccessor;
  @Inject SendEmailUtils sendEmailUtils;

  @Inject
  @Named("base58StringGenerator")
  StringGenerator passwordGenerator;

  @Inject
  @Parameter("clientId")
  Optional<String> clientId;

  @Inject
  @Parameter("email")
  Optional<String> email;

  @Inject
  @Parameter("password")
  Optional<String> optionalPassword;

  @Inject
  ConsoleOteSetupAction() {}

  @Override
  public void runAfterLogin(HashMap<String, Object> data) {
    logger.atInfo().log(
        "User %s is accessing the OT&E setup page. Method= %s",
        registrarAccessor.userIdForLogging(), method);
    checkState(
        !RegistryEnvironment.get().equals(PRODUCTION), "Can't create OT&E in prod");

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

  @Override
  public String getPath() {
    return PATH;
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
    String environment = Ascii.toLowerCase(String.valueOf(RegistryEnvironment.get()));
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
        String.format("OT&E for registrar %s created in %s", clientId.get(), environment),
        builder.toString());
  }
}
