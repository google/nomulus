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
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.POST;
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;
import static org.apache.http.HttpStatus.SC_OK;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.ConsoleUpdateHistory;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.util.DomainNameUtils;
import google.registry.util.RegistryEnvironment;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;

@Action(
    service = Service.CONSOLE,
    path = ConsoleUpdateRegistrarAction.PATH,
    method = {POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleUpdateRegistrarAction extends ConsoleApiAction {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String CHANGE_LOG_ENTRY = "%s updated on %s, old -> %s, new -> %s";
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
              Registrar existingRegistrar =
                  Registrar.loadByRegistrarId(registrarParam.getRegistrarId())
                      .orElseThrow(
                          () ->
                              new IllegalArgumentException(
                                  String.format(
                                      "Registrar %s does not exist",
                                      registrarParam.getRegistrarId())));

              // Only allow modifying allowed TLDs if we're in a non-PRODUCTION environment, if the
              // registrar is not REAL, or the registrar has a RDAP abuse contact set.
              if (!registrarParam.getAllowedTlds().isEmpty()) {
                boolean isRealRegistrar = Registrar.Type.REAL.equals(existingRegistrar.getType());
                if (RegistryEnvironment.PRODUCTION.equals(RegistryEnvironment.get())
                    && isRealRegistrar) {
                  checkArgumentPresent(
                      existingRegistrar.getRdapAbuseContact(),
                      "Cannot modify allowed TLDs if there is no RDAP abuse contact set. Please"
                          + " use the \"nomulus registrar_contact\" command on this registrar to"
                          + " set a RDAP abuse contact.");
                }
              }

              Instant now = tm().getTxTime();
              Instant newLastPocVerificationDate =
                  registrarParam.getLastPocVerificationDate() == null
                      ? START_INSTANT
                      : registrarParam.getLastPocVerificationDate();

              checkArgument(
                  newLastPocVerificationDate.isBefore(now),
                  "Invalid value of LastPocVerificationDate - value is in the future");

              var updatedRegistrarBuilder =
                  existingRegistrar
                      .asBuilder()
                      .setLastPocVerificationDate(newLastPocVerificationDate);

              if (!registrarParam.getAllowedTlds().equals(existingRegistrar.getAllowedTlds())) {
                // The global permission EDIT_REGISTRAR_DETAILS signifies a support agent who *does*
                // have the ability to edit allowed TLDs. See support docs 2.19: "Enabling TLD
                // Access in Production"
                checkGlobalPermission(user, ConsolePermission.EDIT_REGISTRAR_DETAILS);
                updatedRegistrarBuilder.setAllowedTlds(
                    registrarParam.getAllowedTlds().stream()
                        .map(DomainNameUtils::canonicalizeHostname)
                        .collect(toImmutableSet()));
              }

              if (registrarParam.isRegistryLockAllowed()
                  != existingRegistrar.isRegistryLockAllowed()) {
                // Enabling registry lock requires a support lead or FTE, which maps to
                // MANAGE_REGISTRARS. See support docs 2.33: "Registry Lock Onboarding Process"
                checkGlobalPermission(user, ConsolePermission.MANAGE_REGISTRARS);
                updatedRegistrarBuilder.setRegistryLockAllowed(
                    registrarParam.isRegistryLockAllowed());
              }

              var updatedRegistrar = updatedRegistrarBuilder.build();
              tm().put(updatedRegistrar);
              finishAndPersistConsoleUpdateHistory(
                  new ConsoleUpdateHistory.Builder()
                      .setType(ConsoleUpdateHistory.Type.REGISTRAR_UPDATE)
                      .setDescription(updatedRegistrar.getRegistrarId()));

              logConsoleChangesIfNecessary(updatedRegistrar, existingRegistrar);

              sendExternalUpdatesIfNecessary(
                  EmailInfo.create(
                      existingRegistrar, updatedRegistrar, ImmutableSet.of(), ImmutableSet.of()));
            });

    consoleApiParams.response().setStatus(SC_OK);
  }

  private void logConsoleChangesIfNecessary(
      Registrar updatedRegistrar, Registrar existingRegistrar) {
    if (!updatedRegistrar.getAllowedTlds().containsAll(existingRegistrar.getAllowedTlds())) {
      logger.atInfo().log(
          CHANGE_LOG_ENTRY,
          "Allowed TLDs",
          updatedRegistrar.getRegistrarId(),
          existingRegistrar.getAllowedTlds(),
          updatedRegistrar.getAllowedTlds());
    }

    if (updatedRegistrar.isRegistryLockAllowed() != existingRegistrar.isRegistryLockAllowed()) {
      logger.atInfo().log(
          CHANGE_LOG_ENTRY,
          "Registry lock",
          updatedRegistrar.getRegistrarId(),
          existingRegistrar.isRegistryLockAllowed(),
          updatedRegistrar.isRegistryLockAllowed());
    }
  }
}
