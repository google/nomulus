// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.request.auth;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.appengine.api.users.User;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.flogger.FluentLogger;
import dagger.Lazy;
import google.registry.config.RegistryConfig.Config;
import google.registry.groups.GroupsConnection;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.State;
import google.registry.model.registrar.RegistrarPoc;
import java.util.Optional;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

/**
 * Allows access only to {@link Registrar}s the current user has access to.
 *
 * <p>A user has OWNER role on a Registrar if there exists a {@link RegistrarPoc} with that user's
 * gaeId and the registrar as a parent.
 *
 * <p>An "admin" has in addition OWNER role on {@code #registryAdminRegistrarId} and to all
 * non-{@code REAL} registrars (see {@link Registrar#getType}).
 *
 * <p>An "admin" also has ADMIN role on ALL registrars.
 *
 * <p>A user is an "admin" if they are a GAE-admin, or if their email is in the "Support" G Suite
 * group.
 *
 * <p>NOTE: to check whether the user is in the "Support" G Suite group, we need a connection to G
 * Suite. This in turn requires we have valid JsonCredentials, which not all environments have set
 * up. This connection will be created lazily (only if needed).
 *
 * <p>Specifically, we don't instantiate the connection if: (a) gSuiteSupportGroupEmailAddress isn't
 * defined, or (b) the user is logged out, or (c) the user is a GAE-admin, or (d) bypassAdminCheck
 * is true.
 */
@Immutable
public class AuthenticatedRegistrarAccessor {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** The role under which access is granted. */
  public enum Role {
    OWNER,
    ADMIN
  }

  private final String userIdForLogging;

  /**
   * Whether this user is an Admin, meaning either a GAE-admin or a member of the Support G Suite
   * group.
   */
  private final boolean isAdmin;

  /**
   * Gives all roles a user has for a given registrar ID.
   *
   * <p>The order is significant, with "more specific to this user" coming first.
   *
   * <p>Logged out users have an empty roleMap.
   */
  private final ImmutableSetMultimap<String, Role> roleMap;

  /**
   * Bypass the "isAdmin" check making all users NOT admins.
   *
   * <p>Currently our test server doesn't let you change the user after the test server was created.
   * This means we'd need multiple test files to test the same actions as both a "regular" user and
   * an admin.
   *
   * <p>To overcome this - we add a flag that lets you dynamically choose whether a user is an admin
   * or not by creating a fake "GAE-admin" user and then bypassing the admin check if they want to
   * fake a "regular" user.
   *
   * <p>The reason we don't do it the other way around (have a flag that makes anyone an admin) is
   * that such a flag would be a security risk, especially since VisibleForTesting is unenforced
   * (and you could set it with reflection anyway).
   *
   * <p>Instead of having a test flag that elevates permissions (which has security concerns) we add
   * this flag that reduces permissions.
   */
  @VisibleForTesting public static boolean bypassAdminCheck = false;

  @Inject
  public AuthenticatedRegistrarAccessor(
      AuthResult authResult,
      @Config("registryAdminClientId") String registryAdminRegistrarId,
      @Config("gSuiteSupportGroupEmailAddress") Optional<String> gSuiteSupportGroupEmailAddress,
      Lazy<GroupsConnection> lazyGroupsConnection) {
    this.isAdmin = userIsAdmin(authResult, gSuiteSupportGroupEmailAddress, lazyGroupsConnection);

    this.userIdForLogging = authResult.userIdForLogging();
    this.roleMap = createRoleMap(authResult, this.isAdmin, registryAdminRegistrarId);

    logger.atInfo().log("%s has the following roles: %s", userIdForLogging(), roleMap);
  }

  private AuthenticatedRegistrarAccessor(
      String userIdForLogging, boolean isAdmin, ImmutableSetMultimap<String, Role> roleMap) {
    this.userIdForLogging = checkNotNull(userIdForLogging);
    this.roleMap = checkNotNull(roleMap);
    this.isAdmin = isAdmin;
  }

  /**
   * Creates a "logged-in user" accessor with a given role map, used for tests.
   *
   * <p>The user will be allowed to create Registrars (and hence do OT&amp;E setup) iff they have
   * the role of ADMIN for at least one registrar ID.
   *
   * <p>The user's "name" in logs and exception messages is "TestUserId".
   */
  @VisibleForTesting
  public static AuthenticatedRegistrarAccessor createForTesting(
      ImmutableSetMultimap<String, Role> roleMap) {
    boolean isAdmin = roleMap.values().contains(Role.ADMIN);
    return new AuthenticatedRegistrarAccessor("TestUserId", isAdmin, roleMap);
  }

  /**
   * Returns whether this user is allowed to create new Registrars and TLDs.
   */
  public boolean isAdmin() {
    return isAdmin;
  }

  /**
   * A map that gives all roles a user has for a given registrar ID.
   *
   * <p>Throws a {@link RegistrarAccessDeniedException} if the user is not logged in.
   *
   * <p>The result is ordered starting from "most specific to this user".
   *
   * <p>If you want to load the {@link Registrar} object from these (or any other) {@code
   * registrarId}, in order to perform actions on behalf of a user, you must use {@link
   * #getRegistrar} which makes sure the user has permissions.
   *
   * <p>Note that this is an OPTIONAL step in the authentication - only used if we don't have any
   * other clue as to the requested {@code registrarId}. It is perfectly OK to get a {@code
   * registrarId} from any other source, as long as the registrar is then loaded using {@link
   * #getRegistrar}.
   */
  public ImmutableSetMultimap<String, Role> getAllRegistrarIdsWithRoles() {
    return roleMap;
  }

  /**
   * Returns all the roles the current user has on the given registrar.
   *
   * <p>This is syntactic sugar for {@code getAllRegistrarIdsWithRoles().get(registrarId)}.
   */
  public ImmutableSet<Role> getRolesForRegistrar(String registrarId) {
    return getAllRegistrarIdsWithRoles().get(registrarId);
  }

  /**
   * Checks if we have a given role for a given registrar.
   *
   * <p>This is syntactic sugar for {@code getAllRegistrarIdsWithRoles().containsEntry(registrarId,
   * role)}.
   */
  public boolean hasRoleOnRegistrar(Role role, String registrarId) {
    return getAllRegistrarIdsWithRoles().containsEntry(registrarId, role);
  }

  /**
   * "Guesses" which client ID the user wants from all those they have access to.
   *
   * <p>If no such registrar IDs exist, throws a RegistrarAccessDeniedException.
   *
   * <p>This should be the registrar ID "most likely wanted by the user".
   *
   * <p>If you want to load the {@link Registrar} object from this (or any other) {@code
   * registrarId}, in order to perform actions on behalf of a user, you must use {@link
   * #getRegistrar} which makes sure the user has permissions.
   *
   * <p>Note that this is an OPTIONAL step in the authentication - only used if we don't have any
   * other clue as to the requested {@code registrarId}. It is perfectly OK to get a {@code
   * registrarId} from any other source, as long as the registrar is then loaded using {@link
   * #getRegistrar}.
   */
  public String guessRegistrarId() throws RegistrarAccessDeniedException {
    return getAllRegistrarIdsWithRoles().keySet().stream()
        .findFirst()
        .orElseThrow(
            () ->
                new RegistrarAccessDeniedException(
                    String.format("%s isn't associated with any registrar", userIdForLogging)));
  }

  /**
   * Loads a Registrar IFF the user is authorized.
   *
   * <p>Throws a {@link RegistrarAccessDeniedException} if the user is not logged in, or not
   * authorized to access the requested registrar.
   *
   * @param registrarId ID of the registrar we request
   */
  public Registrar getRegistrar(String registrarId) throws RegistrarAccessDeniedException {
    Registrar registrar =
        Registrar.loadByRegistrarId(registrarId)
            .orElseThrow(
                () ->
                    new RegistrarAccessDeniedException(
                        String.format("Registrar %s does not exist", registrarId)));
    verifyAccess(registrarId);

    if (!registrarId.equals(registrar.getRegistrarId())) {
      logger.atSevere().log(
          "registrarLoader.apply(registrarId) returned a Registrar with a different registrarId. "
              + "Requested: %s, returned: %s.",
          registrarId, registrar.getRegistrarId());
      throw new RegistrarAccessDeniedException("Internal error - please check logs");
    }

    return registrar;
  }

  public void verifyAccess(String registrarId) throws RegistrarAccessDeniedException {
    ImmutableSet<Role> roles = getAllRegistrarIdsWithRoles().get(registrarId);

    if (roles.isEmpty()) {
      throw new RegistrarAccessDeniedException(
          String.format("%s doesn't have access to registrar %s", userIdForLogging, registrarId));
    }
    logger.atInfo().log("%s has %s access to registrar %s.", userIdForLogging, roles, registrarId);
  }

  public String userIdForLogging() {
    return userIdForLogging;
  }

  @Override
  public String toString() {
    return toStringHelper(getClass()).add("user", userIdForLogging).toString();
  }

  private static boolean checkIsSupport(
      Lazy<GroupsConnection> lazyGroupsConnection,
      String userEmail,
      Optional<String> gSuiteSupportGroupEmailAddress) {
    if (!gSuiteSupportGroupEmailAddress.isPresent()) {
      return false;
    }
    try {
      return lazyGroupsConnection
          .get()
          .isMemberOfGroup(userEmail, gSuiteSupportGroupEmailAddress.get());
    } catch (RuntimeException e) {
      logger.atSevere().withCause(e).log(
          "Error checking whether email %s belongs to support group %s."
              + " Skipping support role check.",
          userEmail, gSuiteSupportGroupEmailAddress);
      return false;
    }
  }

  private static boolean userIsAdmin(
      AuthResult authResult,
      Optional<String> gSuiteSupportGroupEmailAddress,
      Lazy<GroupsConnection> lazyGroupsConnection) {
    if (!authResult.userAuthInfo().isPresent()) {
      return false;
    }

    UserAuthInfo userAuthInfo = authResult.userAuthInfo().get();
    // both GAE project admin and members of the gSuiteSupportGroupEmailAddress are considered
    // admins for the RegistrarConsole.
    return !bypassAdminCheck
        && (userAuthInfo.isUserAdmin()
            || checkIsSupport(
                lazyGroupsConnection,
                userAuthInfo.getEmailAddress(),
                gSuiteSupportGroupEmailAddress));
  }

  /** Returns a map of registrar IDs to roles for all registrars that the user has access to. */
  private static ImmutableSetMultimap<String, Role> createRoleMap(
      AuthResult authResult, boolean isAdmin, String registryAdminRegistrarId) {
    if (!authResult.userAuthInfo().isPresent()) {
      return ImmutableSetMultimap.of();
    }
    ImmutableSetMultimap.Builder<String, Role> builder = new ImmutableSetMultimap.Builder<>();
    UserAuthInfo userAuthInfo = authResult.userAuthInfo().get();
    if (userAuthInfo.appEngineUser().isPresent()) {
      User user = userAuthInfo.appEngineUser().get();
      logger.atInfo().log("Checking registrar contacts for user ID %s.", user.getUserId());

      // Find all registrars that have a registrar contact with this user's ID.
      jpaTm()
          .transact(
              () ->
                  jpaTm()
                      .query(
                          "SELECT r FROM Registrar r INNER JOIN RegistrarPoc rp ON "
                              + "r.clientIdentifier = rp.registrarId WHERE rp.gaeUserId = "
                              + ":gaeUserId AND r.state != :state",
                          Registrar.class)
                      .setParameter("gaeUserId", user.getUserId())
                      .setParameter("state", State.DISABLED)
                      .getResultStream()
                      .forEach(registrar -> builder.put(registrar.getRegistrarId(), Role.OWNER)));
    } else {
      userAuthInfo
          .consoleUser()
          .get()
          .getUserRoles()
          .getRegistrarRoles()
          .forEach((k, v) -> builder.put(k, Role.OWNER));
    }

    // Admins have ADMIN access to all registrars, and also OWNER access to the registry registrar
    // and all non-REAL or non-live registrars.
    if (isAdmin) {
      tm().transact(
              () ->
                  tm().loadAllOf(Registrar.class)
                      .forEach(
                          registrar -> {
                            if (registrar.getType() != Registrar.Type.REAL
                                || !registrar.isLive()
                                || registrar.getRegistrarId().equals(registryAdminRegistrarId)) {
                              builder.put(registrar.getRegistrarId(), Role.OWNER);
                            }
                            builder.put(registrar.getRegistrarId(), Role.ADMIN);
                          }));
    }

    return builder.build();
  }

  /** Exception thrown when the current user doesn't have access to the requested Registrar. */
  public static class RegistrarAccessDeniedException extends Exception {
    public RegistrarAccessDeniedException(String message) {
      super(message);
    }
  }
}
