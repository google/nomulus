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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.GET;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.base.Strings;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.ConsoleUpdateHistory;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.Action.GkeService;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;

@Action(
    service = GaeService.DEFAULT,
    gkeService = GkeService.CONSOLE,
    path = ConsoleHistoryDataAction.PATH,
    method = {GET},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleHistoryDataAction extends ConsoleApiAction {

  private static final String SQL_USER_HISTORY =
      """
        SELECT * FROM "ConsoleUpdateHistory"
        WHERE acting_user = :actingUser
      """;

  private static final String SQL_REGISTRAR_HISTORY =
      """
      SELECT *
      FROM "ConsoleUpdateHistory"
      WHERE SPLIT_PART(description, '|', 1) = :registrarId;
      """;

  public static final String PATH = "/console-api/history";

  private final String registrarId;
  private final Optional<String> consoleUserEmail;

  @Inject
  public ConsoleHistoryDataAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("registrarId") String registrarId,
      @Parameter("consoleUserEmail") Optional<String> consoleUserEmail) {
    super(consoleApiParams);
    this.registrarId = registrarId;
    this.consoleUserEmail = consoleUserEmail;
  }

  @Override
  protected void getHandler(User user) {
    if (this.consoleUserEmail.isPresent()) {
      this.historyByUser(user, this.consoleUserEmail.get());
      return;
    }

    this.historyByRegistrarId(user, this.registrarId);
  }

  private void historyByUser(User user, String consoleUserEmail) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.AUDIT_ACTIVITY_BY_USER)) {
      setFailedResponse(
          "User doesn't have a permission to check audit activity by user", SC_BAD_REQUEST);
      return;
    }

    List<ConsoleUpdateHistory> queryResult =
        tm().transact(
                () ->
                    tm().getEntityManager()
                        .createNativeQuery(SQL_USER_HISTORY, ConsoleUpdateHistory.class)
                        .setParameter("actingUser", consoleUserEmail)
                        .setHint("org.hibernate.fetchSize", 1000)
                        .getResultList());

    List<ConsoleUpdateHistory> formattedHistoryList =
        replaceActiveUserIfNecessary(queryResult, user);
    consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(formattedHistoryList));
    consoleApiParams.response().setStatus(SC_OK);
  }

  private void historyByRegistrarId(User user, String registrarId) {
    checkArgument(!Strings.isNullOrEmpty(registrarId), "Empty registrarId param");
    checkPermission(user, registrarId, ConsolePermission.AUDIT_ACTIVITY_BY_REGISTRAR);
    List<ConsoleUpdateHistory> queryResult =
        tm().transact(
                () ->
                    tm().getEntityManager()
                        .createNativeQuery(SQL_REGISTRAR_HISTORY, ConsoleUpdateHistory.class)
                        .setParameter("registrarId", registrarId)
                        .setHint("org.hibernate.fetchSize", 1000)
                        .getResultList());

    List<ConsoleUpdateHistory> formattedHistoryList =
        replaceActiveUserIfNecessary(queryResult, user);
    consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(formattedHistoryList));
    consoleApiParams.response().setStatus(SC_OK);
  }

  /** Anonymizes support users in history logs if the user viewing the log is a registrar user. */
  private List<ConsoleUpdateHistory> replaceActiveUserIfNecessary(
      List<ConsoleUpdateHistory> historyList, User requestingUser) {
    // Check if the user *viewing* the history is a registrar user (not a support user)
    if (GlobalRole.NONE.equals(requestingUser.getUserRoles().getGlobalRole())) {
      User genericSupportUser = // Fixed typo
          new User.Builder()
              .setEmailAddress("registry-support@google.com")
              .setUserRoles(new UserRoles.Builder().build()) // Simplified roles
              .build();

      return historyList.stream()
          .map(
              historyEntry -> {
                // Check if the user who performed the action was a support user
                if (!GlobalRole.NONE.equals(
                    historyEntry.getActingUser().getUserRoles().getGlobalRole())) {
                  return historyEntry.asBuilder().setActingUser(genericSupportUser).build();
                }
                // Ff acting user was a registrar user show them as-is
                return historyEntry;
              })
          .collect(toImmutableList());
    }

    // If the viewing user is a support user, return the list unmodified
    return historyList;
  }
}
