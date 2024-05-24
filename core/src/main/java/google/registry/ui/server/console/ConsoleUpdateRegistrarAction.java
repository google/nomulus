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
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;
import static org.apache.http.HttpStatus.SC_OK;

import com.google.common.base.Strings;
import google.registry.groups.GmailClient;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.ui.server.registrar.ConsoleApiParams;
import google.registry.util.DomainNameUtils;
import google.registry.util.EmailMessage;
import google.registry.util.RegistryEnvironment;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;

@Action(
    service = Action.Service.DEFAULT,
    path = ConsoleEppPasswordAction.PATH,
    method = {POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleUpdateRegistrarAction extends ConsoleApiAction {
  static final String PATH = "/console-api/registrar";
  private static final String EMAIL_SUBJ = "Registrar %s has been updated";
  private static final String EMAIL_BODY =
      "The following changes were made in registry %s environment to the registrar %s:";
  private final Optional<Registrar> registrar;

  private final GmailClient gmailClient;

  @Inject
  ConsoleUpdateRegistrarAction(
      ConsoleApiParams consoleApiParams,
      GmailClient gmailClient,
      @Parameter("registrar") Optional<Registrar> registrar) {
    super(consoleApiParams);
    this.registrar = registrar;
    this.gmailClient = gmailClient;
  }

  @Override
  protected void postHandler(User user) {
    var errorMsg = "Missing param(s): %s";
    Registrar updatedRegistrar =
        registrar.orElseThrow(() -> new BadRequestException(String.format(errorMsg, "registrar")));
    checkArgument(
        !Strings.isNullOrEmpty(updatedRegistrar.getRegistrarId()), errorMsg, "registrarId");
    checkPermission(
        user, updatedRegistrar.getRegistrarId(), ConsolePermission.EDIT_REGISTRAR_DETAILS);

    tm().transact(
            () -> {
              Optional<Registrar> existingRegistrar =
                  Registrar.loadByRegistrarId(updatedRegistrar.getRegistrarId());
              checkArgument(
                  !existingRegistrar.isEmpty(),
                  "Registrar with registrarId %s doesn't exists",
                  updatedRegistrar.getRegistrarId());

              // Only allow modifying allowed TLDs if we're in a non-PRODUCTION environment, if the
              // registrar is not REAL, or the registrar has a WHOIS abuse contact set.
              if (!updatedRegistrar.getAllowedTlds().isEmpty()) {
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

              tm().put(
                      existingRegistrar
                          .get()
                          .asBuilder()
                          .setAllowedTlds(
                              updatedRegistrar.getAllowedTlds().stream()
                                  .map(DomainNameUtils::canonicalizeHostname)
                                  .collect(Collectors.toSet()))
                          .setRegistryLockAllowed(updatedRegistrar.isRegistryLockAllowed())
                          .build());

              sendEmail(existingRegistrar.get(), updatedRegistrar);
            });

    consoleApiParams.response().setStatus(SC_OK);
  }

  void sendEmail(Registrar oldRegistrar, Registrar updatedRegistrar) throws AddressException {
    String emailBody =
        String.format(EMAIL_BODY, RegistryEnvironment.get(), oldRegistrar.getRegistrarId());

    StringBuilder diff = new StringBuilder();
    if (oldRegistrar.isRegistryLockAllowed() != updatedRegistrar.isRegistryLockAllowed()) {
      diff.append("/n");
      diff.append(
          String.format(
              "Registry Lock Allowed: %s -> %s",
              oldRegistrar.isRegistryLockAllowed(), updatedRegistrar.isRegistryLockAllowed()));
    }
    if (!oldRegistrar.getAllowedTlds().equals(updatedRegistrar.getAllowedTlds())) {
      diff.append("/n");
      diff.append(
          String.format(
              "Allowed TLDs: %s -> %s",
              oldRegistrar.getAllowedTlds(), updatedRegistrar.getAllowedTlds()));
    }

    if (diff.length() > 0) {
      this.gmailClient.sendEmail(
          EmailMessage.create(
              String.format(EMAIL_SUBJ, oldRegistrar.getRegistrarId()),
              emailBody + diff,
              new InternetAddress(oldRegistrar.getEmailAddress(), true)));
    }
  }
}
