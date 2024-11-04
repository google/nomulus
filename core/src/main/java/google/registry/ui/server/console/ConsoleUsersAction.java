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

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.model.console.RegistrarRole.ACCOUNT_MANAGER;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.DELETE;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static jakarta.servlet.http.HttpServletResponse.SC_CREATED;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.api.services.directory.Directory;
import com.google.api.services.directory.model.UserName;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.annotations.Expose;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import google.registry.request.Action.GkeService;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.tools.IamClient;
import google.registry.util.StringGenerator;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.inject.Inject;
import javax.inject.Named;

@Action(
    service = Action.GaeService.DEFAULT,
    gkeService = GkeService.CONSOLE,
    path = ConsoleUsersAction.PATH,
    method = {GET, POST, DELETE},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleUsersAction extends ConsoleApiAction {
  static final String PATH = "/console-api/users";
  private static final int PASSWORD_LENGTH = 16;

  private static final Splitter EMAIL_SPLITTER = Splitter.on('@').trimResults();
  private final Gson gson;
  private final String registrarId;
  private final Directory directory;
  private final StringGenerator passwordGenerator;
  private final Optional<UserDeleteData> userDeleteData;
  private final Optional<String> maybeGroupEmailAddress;
  private final IamClient iamClient;
  private final String gSuiteDomainName;

  @Inject
  public ConsoleUsersAction(
      ConsoleApiParams consoleApiParams,
      Gson gson,
      Directory directory,
      IamClient iamClient,
      @Config("gSuiteDomainName") String gSuiteDomainName,
      @Config("gSuiteConsoleUserGroupEmailAddress") Optional<String> maybeGroupEmailAddress,
      @Named("base58StringGenerator") StringGenerator passwordGenerator,
      @Parameter("userDeleteData") Optional<UserDeleteData> userDeleteData,
      @Parameter("registrarId") String registrarId) {
    super(consoleApiParams);
    this.gson = gson;
    this.registrarId = registrarId;
    this.directory = directory;
    this.passwordGenerator = passwordGenerator;
    this.userDeleteData = userDeleteData;
    this.maybeGroupEmailAddress = maybeGroupEmailAddress;
    this.iamClient = iamClient;
    this.gSuiteDomainName = gSuiteDomainName;
  }

  @Override
  protected void postHandler(User user) {
    // Temporary flag while testing
    if (user.getUserRoles().isAdmin()) {
      checkPermission(user, registrarId, ConsolePermission.MANAGE_USERS);
      tm().transact(() -> runCreateInTransaction());
    } else {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
    }
  }

  @Override
  protected void getHandler(User user) {
    checkPermission(user, registrarId, ConsolePermission.MANAGE_USERS);
    List<ImmutableMap> users =
        getAllRegistrarUsers(registrarId).stream()
            .map(
                u ->
                    ImmutableMap.of(
                        "emailAddress",
                        u.getEmailAddress(),
                        "role",
                        u.getUserRoles().getRegistrarRoles().get(registrarId)))
            .collect(Collectors.toList());

    consoleApiParams.response().setPayload(gson.toJson(users));
    consoleApiParams.response().setStatus(SC_OK);
  }

  @Override
  protected void deleteHandler(User user) {
    // Temporary flag while testing
    if (user.getUserRoles().isAdmin()) {
      checkPermission(user, registrarId, ConsolePermission.MANAGE_USERS);
      tm().transact(() -> runDeleteInTransaction());
    } else {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
    }
  }

  private void runDeleteInTransaction() throws IOException {
    if (userDeleteData.isEmpty() || isNullOrEmpty(userDeleteData.get().userEmail)) {
      throw new BadRequestException("Missing user data param");
    }
    String email = userDeleteData.get().userEmail;
    User userToDelete =
        tm().loadByKeyIfPresent(VKey.create(User.class, email))
            .orElseThrow(
                () -> new BadRequestException(String.format("User %s doesn't exist", email)));

    if (!userToDelete.getUserRoles().getRegistrarRoles().containsKey(registrarId)) {
      setFailedResponse(
          String.format("Can't delete user not associated with registrarId %s", registrarId),
          SC_FORBIDDEN);
      return;
    }

    try {
      directory.users().delete(email).execute();
    } catch (IOException e) {
      setFailedResponse("Failed to delete the user workspace account", SC_INTERNAL_SERVER_ERROR);
      throw e;
    }

    VKey<User> key = VKey.create(User.class, email);
    tm().delete(key);
    User.revokeIapPermission(email, maybeGroupEmailAddress, cloudTasksUtils, null, iamClient);

    consoleApiParams.response().setStatus(SC_OK);
  }

  private void runCreateInTransaction() throws IOException {
    ImmutableList<User> allRegistrarUsers = getAllRegistrarUsers(registrarId);
    if (allRegistrarUsers.size() >= 4)
      throw new BadRequestException("Total users amount per registrar is limited to 4");

    String nextAvailableEmail =
        IntStream.range(1, 5)
            .mapToObj(i -> String.format("%s-user%s@%s", registrarId, i, gSuiteDomainName))
            .filter(email -> tm().loadByKeyIfPresent(VKey.create(User.class, email)).isEmpty())
            .findFirst()
            // Can only happen if registrar cycled through 20 users, which is unlikely
            .orElseThrow(
                () -> new BadRequestException("Failed to find available increment for new user"));

    com.google.api.services.directory.model.User newUser =
        new com.google.api.services.directory.model.User();

    newUser.setName(
        new UserName()
            .setFamilyName(registrarId)
            .setGivenName(EMAIL_SPLITTER.splitToList(nextAvailableEmail).get(0)));
    newUser.setPassword(passwordGenerator.createString(PASSWORD_LENGTH));
    newUser.setPrimaryEmail(nextAvailableEmail);

    try {
      directory.users().insert(newUser).execute();
    } catch (IOException e) {
      setFailedResponse("Failed to create the user workspace account", SC_INTERNAL_SERVER_ERROR);
      throw e;
    }

    UserRoles userRoles =
        new UserRoles.Builder()
            .setRegistrarRoles(ImmutableMap.of(registrarId, ACCOUNT_MANAGER))
            .build();

    User.Builder builder =
        new User.Builder().setUserRoles(userRoles).setEmailAddress(newUser.getPrimaryEmail());
    tm().put(builder.build());
    User.grantIapPermission(
        nextAvailableEmail, maybeGroupEmailAddress, cloudTasksUtils, null, iamClient);

    consoleApiParams.response().setStatus(SC_CREATED);
    consoleApiParams
        .response()
        .setPayload(
            gson.toJson(
                ImmutableMap.of(
                    "password",
                    newUser.getPassword(),
                    "emailAddress",
                    newUser.getPrimaryEmail(),
                    "role",
                    ACCOUNT_MANAGER)));
  }

  private ImmutableList<User> getAllRegistrarUsers(String registrarId) {
    return tm().transact(
            () ->
                tm().loadAllOf(User.class).stream()
                    .filter(u -> u.getUserRoles().getRegistrarRoles().containsKey(registrarId))
                    .collect(toImmutableList()));
  }

  public record UserDeleteData(@Expose String userEmail) {}
}
