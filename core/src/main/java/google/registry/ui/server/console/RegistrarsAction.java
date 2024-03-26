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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;

import com.google.api.client.http.HttpStatusCodes;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.google.gson.Gson;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarBase.State;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthResult;
import google.registry.ui.server.registrar.JsonGetAction;
import google.registry.util.StringGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Named;

@Action(
    service = Action.Service.DEFAULT,
    path = RegistrarsAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistrarsAction implements JsonGetAction {
  private static final int PASSWORD_LENGTH = 16;
  private static final int PASSCODE_LENGTH = 5;
  static final String PATH = "/console-api/registrars";
  private final AuthResult authResult;
  private final Response response;
  private final Gson gson;
  private final HttpServletRequest req;
  private Optional<Registrar> registrar;
  private StringGenerator passwordGenerator;
  private StringGenerator passcodeGenerator;

  @Inject
  public RegistrarsAction(
      HttpServletRequest req,
      AuthResult authResult,
      Response response,
      Gson gson,
      @Parameter("registrar") Optional<Registrar> registrar,
      @Named("base58StringGenerator") StringGenerator passwordGenerator,
      @Named("digitOnlyStringGenerator") StringGenerator passcodeGenerator) {
    this.authResult = authResult;
    this.response = response;
    this.gson = gson;
    this.registrar = registrar;
    this.req = req;
    this.passcodeGenerator = passcodeGenerator;
    this.passwordGenerator = passwordGenerator;
  }


  @Override
  public void run() {
    User user = authResult.userAuthInfo().get().consoleUser().get();
    if (req.getMethod().equals(GET.toString())) {
      getHandler(user);
    } else {
      postHandler(user);
    }
  }

  private void getHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.VIEW_REGISTRARS)) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_FORBIDDEN);
      return;
    }
    ImmutableList<Registrar> registrars =
        Streams.stream(Registrar.loadAll())
            .filter(r -> r.getType() == Registrar.Type.REAL)
            .collect(ImmutableList.toImmutableList());

    response.setPayload(gson.toJson(registrars));
    response.setStatus(HttpStatusCodes.STATUS_CODE_OK);
  }

  private void postHandler(User user) {
    if (!user.getUserRoles().isAdmin()) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_FORBIDDEN);
      return;
    }

    if (registrar.isEmpty()) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
      response.setPayload(gson.toJson("'registrar' parameter is not present"));
      return;
    }

    Registrar registrarParam = registrar.get();
    String errorMsg = "Missing value for %s";
    try {
      checkArgument(!isNullOrEmpty(registrarParam.getRegistrarId()), errorMsg, "registrarId");
      checkArgument(!isNullOrEmpty(registrarParam.getRegistrarName()), errorMsg, "name");
      checkArgument(!registrarParam.getBillingAccountMap().isEmpty(), errorMsg, "billingAccount");
      checkArgument(registrarParam.getIanaIdentifier() != null, String.format(errorMsg, "ianaId"));
      checkArgument(
          !isNullOrEmpty(registrarParam.getIcannReferralEmail()), errorMsg, "referralEmail");
      checkArgument(!isNullOrEmpty(registrarParam.getDriveFolderId()), errorMsg, "driveId");
      checkArgument(!isNullOrEmpty(registrarParam.getEmailAddress()), errorMsg, "consoleUserEmail");
      checkArgument(
          registrarParam.getLocalizedAddress() != null
              && !isNullOrEmpty(registrarParam.getLocalizedAddress().getState())
              && !isNullOrEmpty(registrarParam.getLocalizedAddress().getCity())
              && !isNullOrEmpty(registrarParam.getLocalizedAddress().getZip())
              && !isNullOrEmpty(registrarParam.getLocalizedAddress().getCountryCode())
              && !registrarParam.getLocalizedAddress().getStreet().isEmpty(),
          errorMsg,
          "address");

      String password = passwordGenerator.createString(PASSWORD_LENGTH);
      String phonePasscode = passcodeGenerator.createString(PASSCODE_LENGTH);

      Registrar registrar =
          new Registrar.Builder()
              .setRegistrarId(registrarParam.getRegistrarId())
              .setRegistrarName(registrarParam.getRegistrarName())
              .setBillingAccountMap(registrarParam.getBillingAccountMap())
              .setIanaIdentifier(Long.valueOf(registrarParam.getIanaIdentifier()))
              .setIcannReferralEmail(registrarParam.getIcannReferralEmail())
              .setEmailAddress(registrarParam.getIcannReferralEmail())
              .setDriveFolderId(registrarParam.getDriveFolderId())
              .setType(Registrar.Type.REAL)
              .setPassword(password)
              .setPhonePasscode(phonePasscode)
              .setState(State.PENDING)
              .setLocalizedAddress(registrarParam.getLocalizedAddress())
              .build();

      RegistrarPoc contact =
          new RegistrarPoc.Builder()
              .setRegistrar(registrar)
              .setName(registrarParam.getEmailAddress())
              .setEmailAddress(registrarParam.getEmailAddress())
              .setLoginEmailAddress(registrarParam.getEmailAddress())
              .build();

      tm().transact(
              () -> {
                checkArgument(
                    Registrar.loadByRegistrarId(registrar.getRegistrarId()).isEmpty(),
                    "Registrar with registrarId %s already exists",
                    registrar.getRegistrarId());
                tm().putAll(registrar, contact);
              });

    } catch (IllegalArgumentException e) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
      response.setPayload(gson.toJson(e.getMessage()));
    } catch (Throwable e) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_SERVER_ERROR);
      response.setPayload(gson.toJson(e.getMessage()));
    }
  }
}
