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
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.ConsoleUpdateHistory;
import google.registry.model.console.RegistrarUpdateHistory;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarBase;
import google.registry.model.registrar.RegistrarBase.State;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.Action.GkeService;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.util.StringGenerator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Named;

@Action(
    service = GaeService.DEFAULT,
    gkeService = GkeService.CONSOLE,
    path = RegistrarsAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistrarsAction extends ConsoleApiAction {
  private static final int PASSWORD_LENGTH = 16;
  private static final int PASSCODE_LENGTH = 5;
  private static final ImmutableList<RegistrarBase.Type> allowedRegistrarTypes =
      ImmutableList.of(Registrar.Type.REAL, RegistrarBase.Type.OTE);
  private static final String SQL_TEMPLATE =
      """
            SELECT * FROM "Registrar"
            WHERE registrar_id in :registrarIds
      """;
  static final String PATH = "/console-api/registrars";
  private final Optional<Registrar> registrar;
  private final StringGenerator passwordGenerator;
  private final StringGenerator passcodeGenerator;

  @Inject
  public RegistrarsAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("registrar") Optional<Registrar> registrar,
      @Named("base58StringGenerator") StringGenerator passwordGenerator,
      @Named("digitOnlyStringGenerator") StringGenerator passcodeGenerator) {
    super(consoleApiParams);
    this.registrar = registrar;
    this.passcodeGenerator = passcodeGenerator;
    this.passwordGenerator = passwordGenerator;
  }

  @Override
  protected void getHandler(User user) {
    if (user.getUserRoles().hasGlobalPermission(ConsolePermission.VIEW_REGISTRARS)) {
      ImmutableList<Registrar> registrars =
          Streams.stream(Registrar.loadAll())
              .filter(r -> allowedRegistrarTypes.contains(r.getType()))
              .collect(ImmutableList.toImmutableList());
      consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(registrars));
      consoleApiParams.response().setStatus(SC_OK);
    } else if (user.getUserRoles().getRegistrarRoles().values().stream()
        .anyMatch(role -> role.hasPermission(ConsolePermission.VIEW_REGISTRAR_DETAILS))) {
      ImmutableSet<String> accessibleRegistrarIds =
          user.getUserRoles().getRegistrarRoles().entrySet().stream()
              .filter(e -> e.getValue().hasPermission(ConsolePermission.VIEW_REGISTRAR_DETAILS))
              .map(Map.Entry::getKey)
              .collect(toImmutableSet());

      List<Registrar> registrars =
          tm().transact(
                  () ->
                      tm().getEntityManager()
                          .createNativeQuery(SQL_TEMPLATE, Registrar.class)
                          .setParameter("registrarIds", accessibleRegistrarIds)
                          .getResultList());

      consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(registrars));
      consoleApiParams.response().setStatus(SC_OK);
    } else {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
    }
  }

  @Override
  protected void postHandler(User user) {
    if (!user.getUserRoles().isAdmin()) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    Registrar registrarParam =
        registrar.orElseThrow(
            () -> new IllegalArgumentException("'registrar' parameter is not present"));
    String errorMsg = "Missing value for %s";
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
            .setIanaIdentifier(registrarParam.getIanaIdentifier())
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
            .build();

    tm().transact(
            () -> {
              checkArgument(
                  Registrar.loadByRegistrarId(registrar.getRegistrarId()).isEmpty(),
                  "Registrar with registrarId %s already exists",
                  registrar.getRegistrarId());
              tm().putAll(registrar, contact);
              finishAndPersistConsoleUpdateHistory(
                  new RegistrarUpdateHistory.Builder()
                      .setType(ConsoleUpdateHistory.Type.REGISTRAR_UPDATE)
                      .setRegistrar(registrar)
                      .setRequestBody(consoleApiParams.gson().toJson(registrar)));
            });
  }

}
