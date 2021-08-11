// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.registrar;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.security.JsonResponseHelper.Status.SUCCESS;
import static google.registry.ui.server.registrar.RegistrarConsoleModule.PARAM_CLIENT_ID;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

import com.google.appengine.api.users.User;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import com.google.gson.Gson;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.tld.RegistryLockDao;
import google.registry.request.Action;
import google.registry.request.Action.Method;
import google.registry.request.Parameter;
import google.registry.request.RequestMethod;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.schema.domain.RegistryLock;
import google.registry.security.JsonResponseHelper;
import java.util.Objects;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * Servlet that allows for getting locks for a particular registrar.
 *
 * <p>Note: at the moment we have no mechanism for JSON GET/POSTs in the same class or at the same
 * URL, which is why this is distinct from the {@link RegistryLockPostAction}.
 */
@Action(
    service = Action.Service.DEFAULT,
    path = RegistryLockGetAction.PATH,
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public final class RegistryLockGetAction implements JsonGetAction {

  public static final String PATH = "/registry-lock-get";

  private static final String LOCK_ENABLED_FOR_CONTACT_PARAM = "lockEnabledForContact";
  private static final String EMAIL_PARAM = "email";
  private static final String LOCKS_PARAM = "locks";
  private static final String DOMAIN_NAME_PARAM = "domainName";
  private static final String LOCKED_TIME_PARAM = "lockedTime";
  private static final String LOCKED_BY_PARAM = "lockedBy";
  private static final String IS_LOCK_PENDING_PARAM = "isLockPending";
  private static final String IS_UNLOCK_PENDING_PARAM = "isUnlockPending";
  private static final String USER_CAN_UNLOCK_PARAM = "userCanUnlock";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Gson GSON = new Gson();

  @VisibleForTesting Method method;
  private final Response response;
  @VisibleForTesting AuthenticatedRegistrarAccessor registrarAccessor;
  @VisibleForTesting AuthResult authResult;
  @VisibleForTesting Optional<String> paramClientId;

  @Inject
  RegistryLockGetAction(
      @RequestMethod Method method,
      Response response,
      AuthenticatedRegistrarAccessor registrarAccessor,
      AuthResult authResult,
      @Parameter(PARAM_CLIENT_ID) Optional<String> paramClientId) {
    this.method = method;
    this.response = response;
    this.registrarAccessor = registrarAccessor;
    this.authResult = authResult;
    this.paramClientId = paramClientId;
  }

  @Override
  public void run() {
    checkArgument(Method.GET.equals(method), "Only GET requests allowed");
    checkArgument(authResult.userAuthInfo().isPresent(), "User auth info must be present");
    checkArgument(paramClientId.isPresent(), "clientId must be present");
    response.setContentType(MediaType.JSON_UTF_8);

    try {
      ImmutableMap<String, ?> resultMap = getLockedDomainsMap(paramClientId.get());
      ImmutableMap<String, ?> payload =
          JsonResponseHelper.create(SUCCESS, "Successful locks retrieval", resultMap);
      response.setPayload(GSON.toJson(payload));
    } catch (RegistrarAccessDeniedException e) {
      logger.atWarning().withCause(e).log(
          "User %s doesn't have access to this registrar", authResult.userIdForLogging());
      response.setStatus(SC_FORBIDDEN);
    } catch (Exception e) {
      logger.atWarning().withCause(e).log("Unexpected error when retrieving locks for a registrar");
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
    }
  }

  static Optional<RegistrarContact> getContactMatchingLogin(User user, Registrar registrar) {
    ImmutableList<RegistrarContact> matchingContacts =
        registrar.getContacts().stream()
            .filter(contact -> contact.getGaeUserId() != null)
            .filter(contact -> Objects.equals(contact.getGaeUserId(), user.getUserId()))
            .collect(toImmutableList());
    if (matchingContacts.size() > 1) {
      ImmutableList<String> matchingEmails =
          matchingContacts.stream()
              .map(RegistrarContact::getEmailAddress)
              .collect(toImmutableList());
      throw new IllegalArgumentException(
          String.format(
              "User ID %s had multiple matching contacts with email addresses %s",
              user.getUserId(), matchingEmails));
    }
    return matchingContacts.stream().findFirst();
  }

  static Registrar getRegistrarAndVerifyLockAccess(
      AuthenticatedRegistrarAccessor registrarAccessor, String clientId, boolean isAdmin)
      throws RegistrarAccessDeniedException {
    Registrar registrar = registrarAccessor.getRegistrar(clientId);
    checkArgument(
        isAdmin || registrar.isRegistryLockAllowed(),
        "Registry lock not allowed for registrar %s",
        clientId);
    return registrar;
  }

  private ImmutableMap<String, ?> getLockedDomainsMap(String clientId)
      throws RegistrarAccessDeniedException {
    // Note: admins always have access to the locks page
    checkArgument(authResult.userAuthInfo().isPresent(), "User auth info must be present");

    boolean isAdmin = registrarAccessor.isAdmin();
    Registrar registrar = getRegistrarAndVerifyLockAccess(registrarAccessor, clientId, isAdmin);
    User user = authResult.userAuthInfo().get().user();

    Optional<RegistrarContact> contactOptional = getContactMatchingLogin(user, registrar);
    boolean isRegistryLockAllowed =
        isAdmin || contactOptional.map(RegistrarContact::isRegistryLockAllowed).orElse(false);
    // Use the contact's registry lock email if it's present, else use the login email (for admins)
    String relevantEmail =
        isAdmin
            ? user.getEmail()
            // if the contact isn't present, we shouldn't display the email anyway so empty is fine
            : contactOptional.flatMap(RegistrarContact::getRegistryLockEmailAddress).orElse("");
    return ImmutableMap.of(
        LOCK_ENABLED_FOR_CONTACT_PARAM,
        isRegistryLockAllowed,
        EMAIL_PARAM,
        relevantEmail,
        PARAM_CLIENT_ID,
        registrar.getClientId(),
        LOCKS_PARAM,
        getLockedDomains(clientId, isAdmin));
  }

  private ImmutableList<ImmutableMap<String, ?>> getLockedDomains(
      String clientId, boolean isAdmin) {
    return jpaTm()
        .transact(
            () ->
                RegistryLockDao.getLocksByRegistrarId(clientId).stream()
                    .filter(lock -> !lock.isLockRequestExpired(jpaTm().getTransactionTime()))
                    .map(lock -> lockToMap(lock, isAdmin))
                    .collect(toImmutableList()));
  }

  private ImmutableMap<String, ?> lockToMap(RegistryLock lock, boolean isAdmin) {
    DateTime now = jpaTm().getTransactionTime();
    return new ImmutableMap.Builder<String, Object>()
        .put(DOMAIN_NAME_PARAM, lock.getDomainName())
        .put(LOCKED_TIME_PARAM, lock.getLockCompletionTime().map(DateTime::toString).orElse(""))
        .put(LOCKED_BY_PARAM, lock.isSuperuser() ? "admin" : lock.getRegistrarPocId())
        .put(IS_LOCK_PENDING_PARAM, !lock.getLockCompletionTime().isPresent())
        .put(
            IS_UNLOCK_PENDING_PARAM,
            lock.getUnlockRequestTime().isPresent()
                && !lock.getUnlockCompletionTime().isPresent()
                && !lock.isUnlockRequestExpired(now))
        .put(USER_CAN_UNLOCK_PARAM, isAdmin || !lock.isSuperuser())
        .build();
  }
}
