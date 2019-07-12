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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.net.HttpHeaders.LOCATION;
import static com.google.common.net.HttpHeaders.X_FRAME_OPTIONS;
import static google.registry.model.common.GaeUserIdConverter.convertEmailAddressToGaeUserId;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_MOVED_TEMPORARILY;

import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserService;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.Resources;
import com.google.common.net.MediaType;
import com.google.template.soy.shared.SoyCssRenamingMap;
import com.google.template.soy.tofu.SoyTofu;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryEnvironment;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registrar.RegistrarContact;
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
import google.registry.ui.soy.registrar.RegistrarCreateConsoleSoyInfo;
import google.registry.util.StringGenerator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import org.joda.money.CurrencyUnit;

/**
 * Action that serves Registrar creation page.
 *
 * <p>This Action does 2 things: - for GET, just returns the form that asks for the required
 * information. - for POST, receives the information and creates the Registrar.
 *
 * <p>TODO(b/120201577): once we can have 2 different Actions with the same path (different
 * Methods), separate this class to 2 Actions.
 */
@Action(
    service = Action.Service.DEFAULT,
    path = ConsoleRegistrarCreatorAction.PATH,
    method = {Method.POST, Method.GET},
    auth = Auth.AUTH_PUBLIC)
public final class ConsoleRegistrarCreatorAction implements Runnable {

  private static final int PASSWORD_LENGTH = 16;
  private static final int PASSCODE_LENGTH = 5;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String PATH = "/registrar-create";

  private static final Supplier<SoyTofu> TOFU_SUPPLIER =
      SoyTemplateUtils.createTofuSupplier(
          google.registry.ui.soy.ConsoleSoyInfo.getInstance(),
          google.registry.ui.soy.FormsSoyInfo.getInstance(),
          google.registry.ui.soy.AnalyticsSoyInfo.getInstance(),
          google.registry.ui.soy.registrar.RegistrarCreateConsoleSoyInfo.getInstance());

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
  @Inject SendEmailUtils sendEmailUtils;
  @Inject @Config("logoFilename") String logoFilename;
  @Inject @Config("productName") String productName;
  @Inject @Config("analyticsConfig") Map<String, Object> analyticsConfig;
  @Inject @Named("base58StringGenerator") StringGenerator passwordGenerator;
  @Inject @Named("digitOnlyStringGenerator") StringGenerator passcodeGenerator;
  @Inject @Parameter("clientId") Optional<String> clientId;
  @Inject @Parameter("name") Optional<String> name;
  @Inject @Parameter("billingAccount") Optional<String> billingAccount;
  @Inject @Parameter("ianaId") Optional<Integer> ianaId;
  @Inject @Parameter("referralEmail") Optional<String> referralEmail;
  @Inject @Parameter("driveId") Optional<String> driveId;
  @Inject @Parameter("consoleUserEmail") Optional<String> consoleUserEmail;

  // Address fields, some of which are required and others are optional.
  @Inject @Parameter("street1") Optional<String> street1;
  @Inject @Parameter("street2") Optional<String> optionalStreet2;
  @Inject @Parameter("street3") Optional<String> optionalStreet3;
  @Inject @Parameter("city") Optional<String> city;
  @Inject @Parameter("state") Optional<String> optionalState;
  @Inject @Parameter("zip") Optional<String> optionalZip;
  @Inject @Parameter("countryCode") Optional<String> countryCode;

  @Inject @Parameter("password") Optional<String> optionalPassword;
  @Inject @Parameter("passcode") Optional<String> optionalPasscode;

  @Inject ConsoleRegistrarCreatorAction() {}

  @Override
  public void run() {
    response.setHeader(X_FRAME_OPTIONS, "SAMEORIGIN");  // Disallow iframing.
    response.setHeader("X-Ui-Compatible", "IE=edge");  // Ask IE not to be silly.

    logger.atInfo().log(
        "User %s is accessing the Registrar creation page. Method= %s",
        registrarAccessor.userIdForLogging(), method);
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
              .newRenderer(RegistrarCreateConsoleSoyInfo.WHOAREYOU)
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

  private void checkPresent(Optional<?> value, String name) {
    checkState(value.isPresent(), "Missing value for %s", name);
  }

  private static final Splitter LINE_SPLITTER =
      Splitter.onPattern("\r?\n").trimResults().omitEmptyStrings();

  private static final Splitter ENTRY_SPLITTER =
      Splitter.on('=').trimResults().omitEmptyStrings().limit(2);

  private static ImmutableMap<CurrencyUnit, String> parseBillingAccount(String billingAccount) {
    try {
      return LINE_SPLITTER.splitToList(billingAccount).stream()
          .map(ENTRY_SPLITTER::splitToList)
          .peek(
              list ->
                  checkState(
                      list.size() == 2,
                      "Can't parse line %s. The format should be [currency]=[account ID]",
                      list))
          .collect(
              toImmutableMap(
                  list -> CurrencyUnit.of(Ascii.toUpperCase(list.get(0))), list -> list.get(1)));
    } catch (Throwable e) {
      throw new RuntimeException("Error parsing billing accounts - " + e.getMessage(), e);
    }
  }

  private void runPost(HashMap<String, Object> data) {
    try {

      checkPresent(clientId, "clientId");
      checkPresent(name, "name");
      checkPresent(billingAccount, "billingAccount");
      checkPresent(ianaId, "ianaId");
      checkPresent(referralEmail, "referralEmail");
      checkPresent(driveId, "driveId");
      checkPresent(consoleUserEmail, "consoleUserEmail");
      checkPresent(street1, "street");
      checkPresent(city, "city");
      checkPresent(countryCode, "countryCode");

      data.put("clientId", clientId.get());
      data.put("name", name.get());
      data.put("ianaId", ianaId.get());
      data.put("referralEmail", referralEmail.get());
      data.put("billingAccount", billingAccount.get());
      data.put("driveId", driveId.get());
      data.put("consoleUserEmail", consoleUserEmail.get());

      data.put("street1", street1.get());
      optionalStreet2.ifPresent(street2 -> data.put("street2", street2));
      optionalStreet3.ifPresent(street3 -> data.put("street3", street3));
      data.put("city", city.get());
      optionalState.ifPresent(state -> data.put("state", state));
      optionalZip.ifPresent(zip -> data.put("zip", zip));
      data.put("countryCode", countryCode.get());

      String gaeUserId =
          checkNotNull(
              convertEmailAddressToGaeUserId(consoleUserEmail.get()),
              "Email address %s is not associated with any GAE ID",
              consoleUserEmail.get());
      String password = optionalPassword.orElse(passwordGenerator.createString(PASSWORD_LENGTH));
      String phonePasscode =
          optionalPasscode.orElse(passcodeGenerator.createString(PASSCODE_LENGTH));
      Registrar registrar =
          new Registrar.Builder()
              .setClientId(clientId.get())
              .setRegistrarName(name.get())
              .setBillingAccountMap(parseBillingAccount(billingAccount.get()))
              .setIanaIdentifier(Long.valueOf(ianaId.get()))
              .setIcannReferralEmail(referralEmail.get())
              .setEmailAddress(referralEmail.get())
              .setDriveFolderId(driveId.get())
              .setType(Registrar.Type.REAL)
              .setPassword(password)
              .setPhonePasscode(phonePasscode)
              .setState(Registrar.State.PENDING)
              .setLocalizedAddress(
                  new RegistrarAddress.Builder()
                      .setStreet(
                          Stream.of(street1, optionalStreet2, optionalStreet3)
                              .filter(Optional::isPresent)
                              .map(Optional::get)
                              .collect(toImmutableList()))
                      .setCity(city.get())
                      .setState(optionalState.orElse(null))
                      .setCountryCode(countryCode.get())
                      .setZip(optionalZip.orElse(null))
                      .build())
              .build();
      RegistrarContact contact =
          new RegistrarContact.Builder()
              .setParent(registrar)
              .setName(consoleUserEmail.get())
              .setEmailAddress(consoleUserEmail.get())
              .setGaeUserId(gaeUserId)
              .build();
      ofy()
          .transact(
              () -> {
                checkState(
                    !Registrar.loadByClientId(registrar.getClientId()).isPresent(),
                    "Registrar with client ID %s already exists",
                    registrar.getClientId());
                ofy().save().entities(registrar, contact);
              });
      data.put("password", password);
      data.put("passcode", phonePasscode);

      sendExternalUpdates();
      response.setPayload(
          TOFU_SUPPLIER
              .get()
              .newRenderer(RegistrarCreateConsoleSoyInfo.RESULT_SUCCESS)
              .setCssRenamingMap(CSS_RENAMING_MAP_SUPPLIER.get())
              .setData(data)
              .render());
    } catch (Throwable e) {
      logger.atWarning().withCause(e).log(
          "Failed to create registrar. clientId: %s, data: %s", clientId.get(), data);
      data.put("errorMessage", e.getMessage());
      response.setPayload(
          TOFU_SUPPLIER
          .get()
          .newRenderer(RegistrarCreateConsoleSoyInfo.FORM_PAGE)
          .setCssRenamingMap(CSS_RENAMING_MAP_SUPPLIER.get())
          .setData(data)
          .render());
    }
  }

  private void runGet(HashMap<String, Object> data) {
    // set the values to pre-fill, if given
    data.put("clientId", clientId.orElse(null));
    data.put("name", name.orElse(null));
    data.put("ianaId", ianaId.orElse(null));
    data.put("referralEmail", referralEmail.orElse(null));
    data.put("driveId", driveId.orElse(null));
    data.put("consoleUserEmail", consoleUserEmail.orElse(null));

    response.setPayload(
        TOFU_SUPPLIER
            .get()
            .newRenderer(RegistrarCreateConsoleSoyInfo.FORM_PAGE)
            .setCssRenamingMap(CSS_RENAMING_MAP_SUPPLIER.get())
            .setData(data)
            .render());
  }

  private String toEmailLine(Optional<?> value, String name) {
    return String.format("    %s: %s\n", name, value.orElse(null));
  }
  private void sendExternalUpdates() {
    if (!sendEmailUtils.hasRecipients()) {
      return;
    }
    String environment = Ascii.toLowerCase(String.valueOf(RegistryEnvironment.get()));
    String body =
        String.format(
                "The following registrar was created in %s by %s:\n",
                environment, registrarAccessor.userIdForLogging())
            + toEmailLine(clientId, "clientId")
            + toEmailLine(name, "name")
            + toEmailLine(billingAccount, "billingAccount")
            + toEmailLine(ianaId, "ianaId")
            + toEmailLine(referralEmail, "referralEmail")
            + toEmailLine(driveId, "driveId")
            + String.format("Gave user %s web access to the registrar\n", consoleUserEmail.get());
    sendEmailUtils.sendEmail(
        String.format("Registrar %s created in %s", clientId.get(), environment), body);
  }
}
