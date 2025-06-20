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

import com.google.gson.annotations.Expose;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.PasswordResetRequest;
import google.registry.model.console.User;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.persistence.transaction.QueryComposer;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.util.EmailMessage;
import jakarta.inject.Inject;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.servlet.http.HttpServletResponse;
import javax.annotation.Nullable;

@Action(
    service = Action.GaeService.DEFAULT,
    gkeService = Action.GkeService.CONSOLE,
    path = PasswordResetRequestAction.PATH,
    method = Action.Method.POST,
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class PasswordResetRequestAction extends ConsoleApiAction {

  static final String PATH = "/console-api/password-reset-request";
  static final String VERIFICATION_EMAIL_TEMPLATE =
      """
      Please click the link below to perform the requested password reset. Note: this\
       code will expire in one hour.

      %s\
      """;

  private final PasswordResetRequestData passwordResetRequestData;

  @Inject
  public PasswordResetRequestAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("passwordResetRequestData") PasswordResetRequestData passwordResetRequestData) {
    super(consoleApiParams);
    this.passwordResetRequestData = passwordResetRequestData;
  }

  @Override
  protected void postHandler(User user) {
    tm().transact(() -> performRequest(user));
    consoleApiParams.response().setStatus(HttpServletResponse.SC_OK);
  }

  private void performRequest(User user) {
    checkArgument(passwordResetRequestData.type != null, "Type cannot be null");
    checkArgument(passwordResetRequestData.registrarId != null, "Registrar ID cannot be null");
    PasswordResetRequest.Type type = passwordResetRequestData.type;
    String registrarId = passwordResetRequestData.registrarId;

    ConsolePermission requiredPermission;
    String destinationEmail;
    String emailSubject;
    switch (type) {
      case EPP:
        requiredPermission = ConsolePermission.EDIT_REGISTRAR_DETAILS;
        destinationEmail = getAdminPocEmail(registrarId);
        emailSubject = "EPP password reset request";
        break;
      case REGISTRY_LOCK:
        checkArgument(
            passwordResetRequestData.registryLockEmail != null,
            "Must provide registry lock email to reset");
        requiredPermission = ConsolePermission.MANAGE_USERS;
        destinationEmail = passwordResetRequestData.registryLockEmail;
        checkUserExistsWithRegistryLockEmail(destinationEmail);
        emailSubject = "Registry lock password reset request";
        break;
      default:
        throw new IllegalArgumentException("Unknown type " + type);
    }

    checkPermission(user, registrarId, requiredPermission);

    InternetAddress destinationAddress;
    try {
      destinationAddress = new InternetAddress(destinationEmail);
    } catch (AddressException e) {
      // Shouldn't happen
      throw new RuntimeException(e);
    }

    PasswordResetRequest resetRequest =
        new PasswordResetRequest.Builder()
            .setRequester(user.getEmailAddress())
            .setRegistrarId(registrarId)
            .setType(type)
            .setDestinationEmail(destinationEmail)
            .build();
    tm().put(resetRequest);
    String verificationUrl =
        String.format(
            "https://%s/console/#/password-reset-verify?verificationCode=%s",
            consoleApiParams.request().getServerName(), resetRequest.getVerificationCode());
    String body = String.format(VERIFICATION_EMAIL_TEMPLATE, verificationUrl);
    consoleApiParams
        .sendEmailUtils()
        .gmailClient
        .sendEmail(EmailMessage.create(emailSubject, body, destinationAddress));
  }

  static User checkUserExistsWithRegistryLockEmail(String destinationEmail) {
    return tm().createQueryComposer(User.class)
        .where("registryLockEmailAddress", QueryComposer.Comparator.EQ, destinationEmail)
        .first()
        .orElseThrow(
            () -> new IllegalArgumentException("Unknown user with lock email " + destinationEmail));
  }

  private String getAdminPocEmail(String registrarId) {
    return RegistrarPoc.loadForRegistrar(registrarId).stream()
        .filter(poc -> poc.getTypes().contains(RegistrarPoc.Type.ADMIN))
        .map(RegistrarPoc::getEmailAddress)
        .findAny()
        .orElseThrow(() -> new IllegalStateException("No admin contacts found for " + registrarId));
  }

  public record PasswordResetRequestData(
      @Expose PasswordResetRequest.Type type,
      @Expose String registrarId,
      @Expose @Nullable String registryLockEmail) {}
}
