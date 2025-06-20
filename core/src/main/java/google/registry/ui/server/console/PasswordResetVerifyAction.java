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
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static google.registry.ui.server.console.PasswordResetRequestAction.checkUserExistsWithRegistryLockEmail;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.PasswordResetRequest;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.joda.time.Duration;

@Action(
    service = Action.GaeService.DEFAULT,
    gkeService = Action.GkeService.CONSOLE,
    path = PasswordResetVerifyAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class PasswordResetVerifyAction extends ConsoleApiAction {

  static final String PATH = "/console-api/password-reset-verify";

  private final String verificationCode;
  private final Optional<String> newPassword;

  @Inject
  public PasswordResetVerifyAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("verificationCode") String verificationCode,
      @Parameter("newPassword") Optional<String> newPassword) {
    super(consoleApiParams);
    this.verificationCode = verificationCode;
    this.newPassword = newPassword;
  }

  @Override
  protected void getHandler(User user) {
    PasswordResetRequest request = tm().transact(() -> loadAndValidateResetRequest(user));
    ImmutableMap<String, ?> result =
        ImmutableMap.of("type", request.getType(), "registrarId", request.getRegistrarId());
    consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(result));
    consoleApiParams.response().setStatus(HttpServletResponse.SC_OK);
  }

  @Override
  protected void postHandler(User user) {
    checkArgument(!Strings.isNullOrEmpty(newPassword.orElse(null)), "Password must be provided");
    tm().transact(
            () -> {
              PasswordResetRequest request = loadAndValidateResetRequest(user);
              switch (request.getType()) {
                case EPP -> handleEppPasswordReset(request);
                case REGISTRY_LOCK -> handleRegistryLockPasswordReset(request);
              }
              tm().put(request.asBuilder().setFulfillmentTime(tm().getTransactionTime()).build());
            });
    consoleApiParams.response().setStatus(HttpServletResponse.SC_OK);
  }

  private void handleEppPasswordReset(PasswordResetRequest request) {
    Registrar registrar = Registrar.loadByRegistrarId(request.getRegistrarId()).get();
    tm().put(registrar.asBuilder().setPassword(newPassword.get()).build());
  }

  private void handleRegistryLockPasswordReset(PasswordResetRequest request) {
    User affectedUser = checkUserExistsWithRegistryLockEmail(request.getDestinationEmail());
    tm().put(
            affectedUser
                .asBuilder()
                .removeRegistryLockPassword()
                .setRegistryLockPassword(newPassword.get())
                .build());
  }

  private PasswordResetRequest loadAndValidateResetRequest(User user) {
    PasswordResetRequest request =
        tm().loadByKeyIfPresent(VKey.create(PasswordResetRequest.class, verificationCode))
            .orElseThrow(this::createVerificationCodeException);
    ConsolePermission requiredVerifyPermission =
        switch (request.getType()) {
          case EPP -> ConsolePermission.MANAGE_USERS;
          case REGISTRY_LOCK -> ConsolePermission.REGISTRY_LOCK;
        };
    checkPermission(user, request.getRegistrarId(), requiredVerifyPermission);
    if (request
        .getRequestTime()
        .plus(Duration.standardHours(1))
        .isBefore(tm().getTransactionTime())) {
      throw createVerificationCodeException();
    }
    return request;
  }

  private IllegalArgumentException createVerificationCodeException() {
    return new IllegalArgumentException(
        "Unknown, invalid, or expired verification code " + verificationCode);
  }
}
