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
import static google.registry.model.console.RegistrarRole.TECH_CONTACT;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.DELETE;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static google.registry.request.Action.Method.PUT;
import static jakarta.servlet.http.HttpServletResponse.SC_CREATED;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.api.services.directory.Directory;
import com.google.api.services.directory.model.UserName;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.annotations.Expose;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.ConsoleUpdateHistory;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrar.Registrar;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.tools.IamClient;
import google.registry.util.DiffUtils;
import google.registry.util.StringGenerator;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

@Action(
    service = Service.CONSOLE,
    path = ConsoleUsersAction.PATH,
    method = {GET, POST, DELETE, PUT},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleUsersAction extends ConsoleApiAction {
  static final String PATH = "/console-api/users";

  private static final int PASSWORD_LENGTH = 16;

  private final String registrarId;
  private final Directory directory;
  private final StringGenerator passwordGenerator;
  private final Optional<UserData> userData;
  private final Optional<String> maybeGroupEmailAddress;
  private final IamClient iamClient;
  private final String gSuiteDomainName;

  @Inject
  public ConsoleUsersAction(
      ConsoleApiParams consoleApiParams,
      Directory directory,
      IamClient iamClient,
      @Config("gSuiteDomainName") String gSuiteDomainName,
      @Config("gSuiteConsoleUserGroupEmailAddress") Optional<String> maybeGroupEmailAddress,
      @Named("base58StringGenerator") StringGenerator passwordGenerator,
      @Parameter("userData") Optional<UserData> userData,
      @Parameter("registrarId") String registrarId) {
    super(consoleApiParams);
    this.registrarId = registrarId;
    this.directory = directory;
    this.passwordGenerator = passwordGenerator;
    this.userData = userData;
    this.maybeGroupEmailAddress = maybeGroupEmailAddress;
    this.iamClient = iamClient;
    this.gSuiteDomainName = gSuiteDomainName;
  }

  @Override
  protected void postHandler(User user) {
    checkPermission(user, registrarId, ConsolePermission.MANAGE_USERS);
    tm().transact(this::runPostInTransaction);
  }

  @Override
  protected void putHandler(User user) {
    checkPermission(user, registrarId, ConsolePermission.MANAGE_USERS);
    tm().transact(this::runUpdateInTransaction);
  }

  @Override
  protected void getHandler(User user) {
    checkPermission(user, registrarId, ConsolePermission.MANAGE_USERS);
    List<UserData> users =
        getAllRegistrarUsers(registrarId).stream()
            .map(
                u ->
                    new UserData(
                        u.getEmailAddress(),
                        u.getRegistryLockEmailAddress().orElse(null),
                        u.getUserRoles().getRegistrarRoles().get(registrarId).toString(),
                        null))
            .collect(Collectors.toList());

    consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(users));
    consoleApiParams.response().setStatus(SC_OK);
  }

  @Override
  protected void deleteHandler(User user) {
    checkPermission(user, registrarId, ConsolePermission.MANAGE_USERS);
    tm().transact(this::runDeleteInTransaction);
  }

  private void runPostInTransaction() throws IOException {
    validateRequestParams();
    if (!tm().exists(VKey.create(User.class, this.userData.get().emailAddress))) {
      this.runCreate();
    } else {
      this.runAppendUserToExistingRegistrar();
    }
  }

  private void runAppendUserToExistingRegistrar() {
    ImmutableList<User> allRegistrarUsers = getAllRegistrarUsers(registrarId);
    if (allRegistrarUsers.size() >= 4) {
      throw new BadRequestException("Total users amount per registrar is limited to 4");
    }

    updateUserRegistrarRoles(
        this.userData.get().emailAddress,
        registrarId,
        requestRoleToAllowedRoles(this.userData.get().role));

    sendConfirmationEmail(registrarId, this.userData.get().emailAddress, "Added existing user");
    consoleApiParams.response().setStatus(SC_OK);
  }

  private void runDeleteInTransaction() throws IOException {
    if (!isModifyingRequestValid()) {
      return;
    }

    String email = this.userData.get().emailAddress;
    User updatedUser = updateUserRegistrarRoles(email, registrarId, null);

    // User has no registrars assigned
    if (updatedUser.getUserRoles().getRegistrarRoles().isEmpty()) {
      try {
        directory.users().delete(email).execute();
      } catch (IOException e) {
        setFailedResponse("Failed to delete the user workspace account", SC_INTERNAL_SERVER_ERROR);
        throw e;
      }

      VKey<User> key = VKey.create(User.class, email);
      tm().delete(key);
      User.revokeIapPermission(email, maybeGroupEmailAddress, cloudTasksUtils, null, iamClient);
      sendConfirmationEmail(registrarId, email, "Deleted user");
      finishAndPersistConsoleUpdateHistory(
          new ConsoleUpdateHistory.Builder()
              .setType(ConsoleUpdateHistory.Type.USER_DELETE)
              .setDescription(
                  String.format(
                      "%s%s%s", registrarId, ConsoleUpdateHistory.DESCRIPTION_SEPARATOR, email)));
    }

    consoleApiParams.response().setStatus(SC_OK);
  }

  private void runCreate() throws IOException {
    ImmutableList<User> allRegistrarUsers = getAllRegistrarUsers(registrarId);
    if (allRegistrarUsers.size() >= 4) {
      throw new BadRequestException("Total users amount per registrar is limited to 4");
    }

    String newEmailPrefix = userData.get().emailAddress.trim();

    if (!newEmailPrefix.matches("^[a-zA-Z0-9]{3}$")) {
      throw new BadRequestException("Email prefix is invalid");
    }

    String newEmail = String.format("%s.%s@%s", newEmailPrefix, registrarId, gSuiteDomainName);
    if (tm().loadByKeyIfPresent(VKey.create(User.class, newEmail)).isPresent()) {
      throw new BadRequestException("Email prefix is not available");
    }

    com.google.api.services.directory.model.User newUser =
        new com.google.api.services.directory.model.User();

    newUser.setName(
        new UserName().setFamilyName(registrarId).setGivenName(newEmailPrefix + "." + registrarId));
    newUser.setPassword(passwordGenerator.createString(PASSWORD_LENGTH));
    newUser.setPrimaryEmail(newEmail);

    try {
      directory.users().insert(newUser).execute();
    } catch (IOException e) {
      setFailedResponse("Failed to create the user workspace account", SC_INTERNAL_SERVER_ERROR);
      throw e;
    }

    RegistrarRole newRole = requestRoleToAllowedRoles(userData.get().role);
    UserRoles userRoles =
        new UserRoles.Builder().setRegistrarRoles(ImmutableMap.of(registrarId, newRole)).build();

    User.Builder builder = new User.Builder().setUserRoles(userRoles).setEmailAddress(newEmail);
    tm().put(builder.build());
    User.grantIapPermission(newEmail, maybeGroupEmailAddress, cloudTasksUtils, null, iamClient);
    sendConfirmationEmail(registrarId, newEmail, "Created user");
    consoleApiParams.response().setStatus(SC_CREATED);
    consoleApiParams
        .response()
        .setPayload(
            consoleApiParams
                .gson()
                .toJson(new UserData(newEmail, null, newRole.toString(), newUser.getPassword())));
    finishAndPersistConsoleUpdateHistory(
        new ConsoleUpdateHistory.Builder()
            .setType(ConsoleUpdateHistory.Type.USER_CREATE)
            .setDescription(
                String.format(
                    "%s%s%s", registrarId, ConsoleUpdateHistory.DESCRIPTION_SEPARATOR, newEmail)));
  }

  private void runUpdateInTransaction() {
    if (!isModifyingRequestValid()) {
      return;
    }

    updateUserRegistrarRoles(
        this.userData.get().emailAddress,
        registrarId,
        requestRoleToAllowedRoles(this.userData.get().role));

    sendConfirmationEmail(registrarId, this.userData.get().emailAddress, "Updated user");
    consoleApiParams.response().setStatus(SC_OK);
    finishAndPersistConsoleUpdateHistory(
        new ConsoleUpdateHistory.Builder()
            .setType(ConsoleUpdateHistory.Type.USER_UPDATE)
            .setDescription(
                String.format(
                    "%s%s%s",
                    registrarId,
                    ConsoleUpdateHistory.DESCRIPTION_SEPARATOR,
                    this.userData.get().emailAddress)));
  }

  private boolean isModifyingRequestValid() {
    validateRequestParams();
    User userToUpdate = verifyUserExists(userData.get().emailAddress);
    return validateUserRegistrarAssociation(userToUpdate);
  }

  private void validateRequestParams() {
    if (userData.isEmpty()
        || isNullOrEmpty(userData.get().emailAddress)
        || isNullOrEmpty(userData.get().role)) {
      throw new BadRequestException("User data is missing or incomplete");
    }
  }

  private User verifyUserExists(String email) {
    return tm().loadByKeyIfPresent(VKey.create(User.class, email))
        .orElseThrow(() -> new BadRequestException(String.format("User %s doesn't exist", email)));
  }

  private boolean validateUserRegistrarAssociation(User user) {
    if (user.getUserRoles().getRegistrarRoles().containsKey(registrarId)) {
      return true;
    }
    setFailedResponse(
        String.format("Can't update user not associated with registrarId %s", registrarId),
        SC_FORBIDDEN);
    return false;
  }

  private User updateUserRegistrarRoles(String email, String registrarId, RegistrarRole newRole) {
    Map<String, RegistrarRole> updatedRegistrarRoles;
    User user = verifyUserExists(email);
    if (newRole == null) {
      updatedRegistrarRoles =
          user.getUserRoles().getRegistrarRoles().entrySet().stream()
              .filter(entry -> !Objects.equals(entry.getKey(), registrarId))
              .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
    } else {
      updatedRegistrarRoles =
          ImmutableMap.<String, RegistrarRole>builder()
              .putAll(user.getUserRoles().getRegistrarRoles())
              .put(registrarId, newRole)
              .buildKeepingLast();
    }
    var updatedUser =
        user.asBuilder()
            .setUserRoles(
                user.getUserRoles().asBuilder().setRegistrarRoles(updatedRegistrarRoles).build())
            .build();
    tm().put(updatedUser);
    return updatedUser;
  }

  private ImmutableList<User> getAllRegistrarUsers(String registrarId) {
    return tm().transact(
            () ->
                tm().loadAllOf(User.class).stream()
                    .filter(u -> u.getUserRoles().getRegistrarRoles().containsKey(registrarId))
                    .collect(toImmutableList()));
  }

  /** Maps a request role string to a RegistrarRole, using ACCOUNT_MANAGER as the default. */
  private RegistrarRole requestRoleToAllowedRoles(String role) {
    return TECH_CONTACT.name().equals(role) ? TECH_CONTACT : ACCOUNT_MANAGER;
  }

  private boolean sendConfirmationEmail(String registrarId, String emailAddress, String operation) {
    Optional<Registrar> registrar = Registrar.loadByRegistrarId(registrarId);
    if (registrar.isEmpty()) { // Shouldn't happen, but worth checking
      setFailedResponse(
          "Failed to send an email to registrar " + registrarId, SC_INTERNAL_SERVER_ERROR);
      return false;
    }
    sendExternalUpdates(
        ImmutableMap.of("Console users updated", new DiffUtils.DiffPair(operation, emailAddress)),
        registrar.get(),
        ImmutableSet.of());
    return true;
  }

  public record UserData(
      @Expose String emailAddress,
      @Expose String registryLockEmailAddress,
      @Expose String role,
      @Expose @Nullable String password) {}
}
