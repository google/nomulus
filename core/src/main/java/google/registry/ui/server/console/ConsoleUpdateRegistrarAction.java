// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.POST;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;
import static org.apache.http.HttpStatus.SC_OK;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.ConsoleUpdateHistory;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.Action.GkeService;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.util.DomainNameUtils;
import google.registry.util.RegistryEnvironment;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.stream.Collectors;
import org.joda.time.DateTime;

@Action(
    service = GaeService.DEFAULT,
    gkeService = GkeService.CONSOLE,
    path = ConsoleUpdateRegistrarAction.PATH,
    method = {POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleUpdateRegistrarAction extends ConsoleApiAction {
  static final String PATH = "/console-api/registrar";
  private final Optional<Registrar> registrar;

  @Inject
  ConsoleUpdateRegistrarAction(
      ConsoleApiParams consoleApiParams, @Parameter("registrar") Optional<Registrar> registrar) {
    super(consoleApiParams);
    this.registrar = registrar;
  }

  @Override
  protected void postHandler(User user) {
    var errorMsg = "Missing param(s): %s";
    Registrar registrarParam =
        registrar.orElseThrow(() -> new BadRequestException(String.format(errorMsg, "registrar")));
    checkArgument(!Strings.isNullOrEmpty(registrarParam.getRegistrarId()), errorMsg, "registrarId");
    checkPermission(
        user, registrarParam.getRegistrarId(), ConsolePermission.EDIT_REGISTRAR_DETAILS);

    tm().transact(
            () -> {
              Optional<Registrar> existingRegistrar =
                  Registrar.loadByRegistrarId(registrarParam.getRegistrarId());
              checkArgument(
                  !existingRegistrar.isEmpty(),
                  "Registrar with registrarId %s doesn't exists",
                  registrarParam.getRegistrarId());

              // Only allow modifying allowed TLDs if we're in a non-PRODUCTION environment, if the
              // registrar is not REAL, or the registrar has a WHOIS abuse contact set.
              if (!registrarParam.getAllowedTlds().isEmpty()) {
                boolean isRealRegistrar =
                    Registrar.Type.REAL.equals(existingRegistrar.get().getType());
                if (RegistryEnvironment.PRODUCTION.equals(RegistryEnvironment.get())
                    && isRealRegistrar) {
                  checkArgumentPresent(
                      existingRegistrar.get().getWhoisAbuseContact(),
                      "Cannot modify allowed TLDs if there is no WHOIS abuse contact set. Please"
                          + " use the \"nomulus registrar_contact\" command on this registrar to"
                          + " set a WHOIS abuse contact.");
                }
              }

              DateTime now = tm().getTransactionTime();
              DateTime newLastPocVerificationDate =
                  registrarParam.getLastPocVerificationDate() == null
                      ? START_OF_TIME
                      : registrarParam.getLastPocVerificationDate();

              checkArgument(
                  newLastPocVerificationDate.isBefore(now),
                  "Invalid value of LastPocVerificationDate - value is in the future");

              var updatedRegistrarBuilder =
                  existingRegistrar
                      .get()
                      .asBuilder()
                      .setLastPocVerificationDate(newLastPocVerificationDate);

              if (user.getUserRoles()
                  .hasGlobalPermission(ConsolePermission.EDIT_REGISTRAR_DETAILS)) {
                updatedRegistrarBuilder =
                    updatedRegistrarBuilder
                        .setAllowedTlds(
                            registrarParam.getAllowedTlds().stream()
                                .map(DomainNameUtils::canonicalizeHostname)
                                .collect(Collectors.toSet()))
                        .setRegistryLockAllowed(registrarParam.isRegistryLockAllowed())
                        .setLastPocVerificationDate(newLastPocVerificationDate);
              }

              var updatedRegistrar = updatedRegistrarBuilder.build();
              tm().put(updatedRegistrar);
              finishAndPersistConsoleUpdateHistory(
                  new ConsoleUpdateHistory.Builder()
                      .setType(ConsoleUpdateHistory.Type.REGISTRAR_UPDATE)
                      .setDescription(updatedRegistrar.getRegistrarId()));
              sendExternalUpdatesIfNecessary(
                  EmailInfo.create(
                      existingRegistrar.get(),
                      updatedRegistrar,
                      ImmutableSet.of(),
                      ImmutableSet.of()));
            });

    consoleApiParams.response().setStatus(SC_OK);
  }
}
