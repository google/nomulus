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

package google.registry.ui.server.console.settings;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.POST;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.Action.GkeService;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.ui.server.console.ConsoleApiAction;
import google.registry.ui.server.console.ConsoleApiParams;
import java.util.Objects;
import java.util.Optional;
import javax.inject.Inject;

/**
 * Console action for editing fields on a registrar that are visible in WHOIS/RDAP.
 *
 * <p>This doesn't cover many of the registrar fields but rather only those that are visible in
 * WHOIS/RDAP and don't have any other obvious means of edit.
 */
@Action(
    service = GaeService.DEFAULT,
    gkeService = GkeService.CONSOLE,
    path = WhoisRegistrarFieldsAction.PATH,
    method = {POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class WhoisRegistrarFieldsAction extends ConsoleApiAction {

  static final String PATH = "/console-api/settings/whois-fields";
  private final AuthenticatedRegistrarAccessor registrarAccessor;
  private final Optional<Registrar> registrar;

  @Inject
  public WhoisRegistrarFieldsAction(
      ConsoleApiParams consoleApiParams,
      AuthenticatedRegistrarAccessor registrarAccessor,
      @Parameter("registrar") Optional<Registrar> registrar) {
    super(consoleApiParams);
    this.registrarAccessor = registrarAccessor;
    this.registrar = registrar;
  }

  @Override
  protected void postHandler(User user) {
    checkArgument(registrar.isPresent(), "'registrar' parameter is not present");
    checkPermission(
        user, registrar.get().getRegistrarId(), ConsolePermission.EDIT_REGISTRAR_DETAILS);
    tm().transact(() -> loadAndModifyRegistrar(registrar.get(), user));
  }

  private void loadAndModifyRegistrar(Registrar providedRegistrar, User user) {
    Registrar savedRegistrar;
    try {
      // reload to make sure the object has all the correct fields
      savedRegistrar = registrarAccessor.getRegistrar(providedRegistrar.getRegistrarId());
    } catch (RegistrarAccessDeniedException e) {
      setFailedResponse(e.getMessage(), SC_FORBIDDEN);
      return;
    }

    // icannReferralEmail can't be updated by partners, only by global users with
    // EDIT_REGISTRAR_DETAILS permission
    if (!Objects.equals(
            savedRegistrar.getIcannReferralEmail(), providedRegistrar.getIcannReferralEmail())
        && !user.getUserRoles().hasGlobalPermission(ConsolePermission.EDIT_REGISTRAR_DETAILS)) {
      setFailedResponse(
          "Icann Referral Email update is not permitted for this user", SC_BAD_REQUEST);
    }

    Registrar newRegistrar =
        savedRegistrar
            .asBuilder()
            .setWhoisServer(providedRegistrar.getWhoisServer())
            .setUrl(providedRegistrar.getUrl())
            .setLocalizedAddress(providedRegistrar.getLocalizedAddress())
            .setPhoneNumber(providedRegistrar.getPhoneNumber())
            .setFaxNumber(providedRegistrar.getFaxNumber())
            .setIcannReferralEmail(providedRegistrar.getIcannReferralEmail())
            .setEmailAddress(providedRegistrar.getEmailAddress())
            .build();
    tm().put(newRegistrar);
    sendExternalUpdatesIfNecessary(
        EmailInfo.create(
            savedRegistrar,
            newRegistrar,
            savedRegistrar.getContacts(),
            savedRegistrar.getContacts()));
    consoleApiParams.response().setStatus(SC_OK);
  }
}
