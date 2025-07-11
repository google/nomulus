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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.GET;

import com.google.common.base.Ascii;
import com.google.gson.annotations.Expose;
import google.registry.model.console.ConsoleUpdateHistory;
import google.registry.model.console.User;
import google.registry.model.domain.RegistryLock;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.Action.GkeService;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.tools.DomainLockUtils;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletResponse;

/** Handler for verifying registry lock requests, a form of 2FA. */
@Action(
    service = GaeService.DEFAULT,
    gkeService = GkeService.CONSOLE,
    path = ConsoleRegistryLockVerifyAction.PATH,
    method = {GET},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleRegistryLockVerifyAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-lock-verify";

  private final DomainLockUtils domainLockUtils;
  private final String lockVerificationCode;

  @Inject
  public ConsoleRegistryLockVerifyAction(
      ConsoleApiParams consoleApiParams,
      DomainLockUtils domainLockUtils,
      @Parameter("lockVerificationCode") String lockVerificationCode) {
    super(consoleApiParams);
    this.domainLockUtils = domainLockUtils;
    this.lockVerificationCode = lockVerificationCode;
  }

  @Override
  protected void getHandler(User user) {
    RegistryLock lock =
        domainLockUtils.verifyVerificationCode(lockVerificationCode, user.getUserRoles().isAdmin());
    RegistryLockAction action =
        lock.getUnlockCompletionTime().isPresent()
            ? RegistryLockAction.UNLOCKED
            : RegistryLockAction.LOCKED;
    RegistryLockVerificationResponse lockResponse =
        new RegistryLockVerificationResponse(
            Ascii.toLowerCase(action.toString()), lock.getDomainName(), lock.getRegistrarId());
    tm().transact(
            () -> {
              finishAndPersistConsoleUpdateHistory(
                  new ConsoleUpdateHistory.Builder()
                      .setType(
                          action == RegistryLockAction.LOCKED
                              ? ConsoleUpdateHistory.Type.REGISTRY_LOCK
                              : ConsoleUpdateHistory.Type.REGISTRY_UNLOCK)
                      .setDescription(
                          String.format(
                              "%s%s%s",
                              lock.getRegistrarId(),
                              ConsoleUpdateHistory.DESCRIPTION_SEPARATOR,
                              lockResponse)));
            });
    consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(lockResponse));
    consoleApiParams.response().setStatus(HttpServletResponse.SC_OK);
  }

  private enum RegistryLockAction {
    LOCKED,
    UNLOCKED
  }

  private record RegistryLockVerificationResponse(
      @Expose String action, @Expose String domainName, @Expose String registrarId) {}
}
