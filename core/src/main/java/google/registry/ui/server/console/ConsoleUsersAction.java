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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.model.console.RegistrarRole.ACCOUNT_MANAGER;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.api.services.directory.Directory;
import com.google.api.services.directory.model.UserName;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import google.registry.request.Action.GkeService;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.util.StringGenerator;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Named;

@Action(
    service = Action.GaeService.DEFAULT,
    gkeService = GkeService.CONSOLE,
    path = ConsoleUsersAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleUsersAction extends ConsoleApiAction {
  static final String PATH = "/console-api/users";
  private static final int PASSWORD_LENGTH = 16;

  private static final Splitter EMAIL_SPLITTER = Splitter.on('@').trimResults();

  private final String registrarId;
  private final Directory directory;
  private final StringGenerator passwordGenerator;

  @Inject
  public ConsoleUsersAction(
      ConsoleApiParams consoleApiParams,
      Directory directory,
      @Named("base58StringGenerator") StringGenerator passwordGenerator,
      @Parameter("registrarId") String registrarId) {
    super(consoleApiParams);
    this.registrarId = registrarId;
    this.directory = directory;
    this.passwordGenerator = passwordGenerator;
  }

  private static String generateNewEmailAddress(User user, String increment) {
    List<String> emailParts = EMAIL_SPLITTER.splitToList(user.getEmailAddress());
    return String.format("%s-%s@%s", emailParts.get(0), increment, emailParts.get(1));
  }

  @Override
  protected void postHandler(User user) {
    // Temporary flag while testing
    if (user.getUserRoles().isAdmin()) {
      checkPermission(user, registrarId, ConsolePermission.MANAGE_USERS);
      tm().transact(() -> runInTransaction(user));
    } else {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
    }
  }

  @Override
  protected void getHandler(User user) {
    checkPermission(user, registrarId, ConsolePermission.MANAGE_USERS);
    List<ImmutableMap> users =
        getAllUsers().stream()
            .filter(u -> u.getUserRoles().getRegistrarRoles().containsKey(registrarId))
            .map(
                u ->
                    ImmutableMap.of(
                        "emailAddress",
                        u.getEmailAddress(),
                        "role",
                        u.getUserRoles().getRegistrarRoles().get(registrarId)))
            .collect(Collectors.toList());
    consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(users));
    consoleApiParams.response().setStatus(SC_OK);
  }

  private void runInTransaction(User user) throws IOException {
    String nextAvailableIncrement =
        Stream.of("1", "2", "3")
            .filter(
                increment ->
                    tm().loadByKeyIfPresent(
                            VKey.create(User.class, generateNewEmailAddress(user, increment)))
                        .isEmpty())
            .findFirst()
            .orElseThrow(() -> new BadRequestException("Extra users amount is limited to 3"));

    com.google.api.services.directory.model.User newUser =
        new com.google.api.services.directory.model.User();

    newUser.setName(
        new UserName().setFamilyName(registrarId).setGivenName("User" + nextAvailableIncrement));
    newUser.setPassword(passwordGenerator.createString(PASSWORD_LENGTH));
    newUser.setPrimaryEmail(generateNewEmailAddress(user, nextAvailableIncrement));

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

    consoleApiParams.response().setStatus(SC_OK);
    consoleApiParams
        .response()
        .setPayload(
            consoleApiParams
                .gson()
                .toJson(
                    ImmutableMap.of(
                        "password",
                        newUser.getPassword(),
                        "emailAddress",
                        newUser.getPrimaryEmail(),
                        "role",
                        ACCOUNT_MANAGER)));
  }

  private ImmutableList<User> getAllUsers() {
    return tm().transact(
            () ->
                tm().loadAllOf(User.class).stream()
                    .filter(u -> !u.getUserRoles().getRegistrarRoles().isEmpty())
                    .collect(toImmutableList()));
  }
}
