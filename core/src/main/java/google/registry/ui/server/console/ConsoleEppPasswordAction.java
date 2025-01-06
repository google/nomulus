// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.annotations.Expose;
import google.registry.flows.EppException.AuthenticationErrorException;
import google.registry.flows.PasswordOnlyTransportCredentials;
import google.registry.model.console.ConsoleUpdateHistory;
import google.registry.model.console.RegistrarUpdateHistory;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.Action.GkeService;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.util.DiffUtils;
import java.util.Optional;
import javax.inject.Inject;

@Action(
    service = GaeService.DEFAULT,
    gkeService = GkeService.CONSOLE,
    path = ConsoleEppPasswordAction.PATH,
    method = {POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleEppPasswordAction extends ConsoleApiAction {

  public static final String PATH = "/console-api/eppPassword";

  private final PasswordOnlyTransportCredentials credentials =
      new PasswordOnlyTransportCredentials();
  private final AuthenticatedRegistrarAccessor registrarAccessor;
  private final Optional<EppPasswordData> eppPasswordChangeRequest;

  @Inject
  public ConsoleEppPasswordAction(
      ConsoleApiParams consoleApiParams,
      AuthenticatedRegistrarAccessor registrarAccessor,
      @Parameter("eppPasswordChangeRequest") Optional<EppPasswordData> eppPasswordChangeRequest) {
    super(consoleApiParams);
    this.registrarAccessor = registrarAccessor;
    this.eppPasswordChangeRequest = eppPasswordChangeRequest;
  }

  private void confirmParamsAvailable() {
    checkArgument(this.eppPasswordChangeRequest.isPresent(), "Epp Password update body is invalid");
    var eppRequestBody = this.eppPasswordChangeRequest.get();
    var errorMsg = "Missing param(s): %s";
    checkArgument(!Strings.isNullOrEmpty(eppRequestBody.registrarId()), errorMsg, "registrarId");
    checkArgument(!Strings.isNullOrEmpty(eppRequestBody.oldPassword()), errorMsg, "oldPassword");
    checkArgument(!Strings.isNullOrEmpty(eppRequestBody.newPassword()), errorMsg, "newPassword");
    checkArgument(
        !Strings.isNullOrEmpty(eppRequestBody.newPasswordRepeat()), errorMsg, "newPasswordRepeat");
  }

  @Override
  protected void postHandler(User user) {
    this.confirmParamsAvailable();

    var eppRequestBody = this.eppPasswordChangeRequest.get();
    checkArgument(
        eppRequestBody.newPassword().equals(eppRequestBody.newPasswordRepeat()),
        "New password fields don't match");

    Registrar registrar;
    try {
      registrar = registrarAccessor.getRegistrar(eppRequestBody.registrarId());
    } catch (RegistrarAccessDeniedException e) {
      setFailedResponse(e.getMessage(), SC_NOT_FOUND);
      return;
    }

    try {
      credentials.validate(registrar, eppRequestBody.oldPassword());
    } catch (AuthenticationErrorException e) {
      setFailedResponse(e.getMessage(), SC_FORBIDDEN);
      return;
    }

    tm().transact(
            () -> {
              Registrar updatedRegistrar =
                  registrar.asBuilder().setPassword(eppRequestBody.newPassword()).build();
              tm().put(updatedRegistrar);
              EppPasswordData sanitizedData =
                  new EppPasswordData(
                      eppRequestBody.registrarId, "********", "••••••••", "••••••••");
              finishAndPersistConsoleUpdateHistory(
                  new RegistrarUpdateHistory.Builder()
                      .setType(ConsoleUpdateHistory.Type.REGISTRAR_UPDATE)
                      .setRegistrar(updatedRegistrar)
                      .setRequestBody(consoleApiParams.gson().toJson(sanitizedData)));
              sendExternalUpdates(
                  ImmutableMap.of("password", new DiffUtils.DiffPair("********", "••••••••")),
                  registrar,
                  ImmutableSet.of());
            });

    consoleApiParams.response().setStatus(SC_OK);
  }

  public record EppPasswordData(
      @Expose String registrarId,
      @Expose String oldPassword,
      @Expose String newPassword,
      @Expose String newPasswordRepeat) {}
}
