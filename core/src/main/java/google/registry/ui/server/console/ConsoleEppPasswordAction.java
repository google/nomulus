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

import com.google.api.client.http.HttpStatusCodes;
import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import google.registry.flows.EppException.AuthenticationErrorException;
import google.registry.flows.PasswordOnlyTransportCredentials;
import google.registry.groups.GmailClient;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.ui.server.registrar.ConsoleApiParams;
import google.registry.util.EmailMessage;
import java.util.Optional;
import javax.inject.Inject;
import javax.mail.internet.InternetAddress;

@Action(
    service = Action.Service.DEFAULT,
    path = ConsoleEppPasswordAction.PATH,
    method = {POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleEppPasswordAction extends ConsoleApiAction {
  protected static final String EMAIL_SUBJ = "EPP password update confirmation";
  protected static final String EMAIL_BODY =
      "Dear %s,\n" + "This is to confirm that your account password has been changed.";

  public static final String PATH = "/console-api/eppPassword";

  private final PasswordOnlyTransportCredentials credentials =
      new PasswordOnlyTransportCredentials();
  private final AuthenticatedRegistrarAccessor registrarAccessor;
  private final GmailClient gmailClient;

  private final Optional<PasswordChangeRequest> eppPasswordChangeRequest;

  @Inject
  public ConsoleEppPasswordAction(
      ConsoleApiParams consoleApiParams,
      AuthenticatedRegistrarAccessor registrarAccessor,
      GmailClient gmailClient,
      @Parameter("eppPasswordChangeRequest")
          Optional<PasswordChangeRequest> eppPasswordChangeRequest) {
    super(consoleApiParams);
    this.registrarAccessor = registrarAccessor;
    this.gmailClient = gmailClient;
    this.eppPasswordChangeRequest = eppPasswordChangeRequest;
  }

  private boolean confirmParamsAvailable() {
    if (!this.eppPasswordChangeRequest.isPresent()) {
      throw new BadRequestException("Epp Password update body is invalid");
    }
    var eppRequestBody = this.eppPasswordChangeRequest.get();
    var errorMsg = "Missing param(s): %s";
    checkArgument(!Strings.isNullOrEmpty(eppRequestBody.registrarId()), errorMsg, "registrarId");
    checkArgument(!Strings.isNullOrEmpty(eppRequestBody.oldPassword()), errorMsg, "oldPassword");
    checkArgument(!Strings.isNullOrEmpty(eppRequestBody.newPassword()), errorMsg, "newPassword");
    checkArgument(
        !Strings.isNullOrEmpty(eppRequestBody.newPasswordRepeat()), errorMsg, "newPasswordRepeat");

    return true;
  }

  @Override
  protected void postHandler(User user) {
    if (!this.confirmParamsAvailable()) return;
    var eppRequestBody = this.eppPasswordChangeRequest.get();
    if (!eppRequestBody.newPassword().equals(eppRequestBody.newPasswordRepeat())) {
      throw new BadRequestException("New password fields don't match");
    }

    Registrar registrar;
    try {
      registrar = registrarAccessor.getRegistrar(eppRequestBody.registrarId());
    } catch (RegistrarAccessDeniedException e) {
      setFailedResponse(e.getMessage(), HttpStatusCodes.STATUS_CODE_NOT_FOUND);
      return;
    }

    try {
      credentials.validate(registrar, eppRequestBody.oldPassword());
    } catch (AuthenticationErrorException e) {
      setFailedResponse(e.getMessage(), HttpStatusCodes.STATUS_CODE_FORBIDDEN);
      return;
    }

    tm().transact(
            () -> {
              tm().put(registrar.asBuilder().setPassword(eppRequestBody.newPassword()).build());
              this.gmailClient.sendEmail(
                  EmailMessage.create(
                      EMAIL_SUBJ,
                      String.format(EMAIL_BODY, registrar.getRegistrarName()),
                      new InternetAddress(registrar.getEmailAddress(), true)));
            });

    consoleApiParams.response().setStatus(HttpStatusCodes.STATUS_CODE_OK);
  }

  public record PasswordChangeRequest(
      String registrarId, String oldPassword, String newPassword, String newPasswordRepeat) {}
}
