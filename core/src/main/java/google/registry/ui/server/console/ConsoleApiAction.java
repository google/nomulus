// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.request.Action.Method.DELETE;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;

import com.google.common.base.Ascii;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.flogger.FluentLogger;
import google.registry.batch.CloudTasksUtils;
import google.registry.config.RegistryConfig;
import google.registry.export.sheet.SyncRegistrarsSheetAction;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.model.registrar.RegistrarPocBase;
import google.registry.request.HttpException;
import google.registry.security.XsrfTokenManager;
import google.registry.util.DiffUtils;
import google.registry.util.RegistryEnvironment;
import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.inject.Inject;

/** Base class for handling Console API requests */
public abstract class ConsoleApiAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  protected ConsoleApiParams consoleApiParams;

  @Inject CloudTasksUtils cloudTasksUtils;

  @Inject
  @RegistryConfig.Config("registryAdminClientId")
  String registryAdminClientId;

  public ConsoleApiAction(ConsoleApiParams consoleApiParams) {
    this.consoleApiParams = consoleApiParams;
  }

  @Override
  public final void run() {
    // Shouldn't be even possible because of Auth annotations on the various implementing classes
    if (consoleApiParams.authResult().user().isEmpty()) {
      consoleApiParams.response().setStatus(SC_UNAUTHORIZED);
      return;
    }
    User user = consoleApiParams.authResult().user().get();
    String requestMethod = consoleApiParams.request().getMethod();
    try {
      if (requestMethod.equals(GET.toString())) {
        getHandler(user);
      } else {
        if (verifyXSRF(user)) {
          if (requestMethod.equals(DELETE.toString())) {
            deleteHandler(user);
          } else {
            postHandler(user);
          }
        }
      }
    } catch (ConsolePermissionForbiddenException e) {
      logger.atWarning().withCause(e).log("Forbidden");
      setFailedResponse("", SC_FORBIDDEN);
    } catch (HttpException.BadRequestException | IllegalArgumentException e) {
      logger.atWarning().withCause(e).log("Error in request");
      setFailedResponse(Throwables.getRootCause(e).getMessage(), SC_BAD_REQUEST);
    } catch (Throwable t) {
      logger.atWarning().withCause(t).log("Internal server error");
      setFailedResponse(Throwables.getRootCause(t).getMessage(), SC_INTERNAL_SERVER_ERROR);
    }
  }

  protected void checkPermission(User user, String registrarId, ConsolePermission permission) {
    if (!user.getUserRoles().hasPermission(registrarId, permission)) {
      throw new ConsolePermissionForbiddenException(
          String.format(
              "User %s does not have permission %s on registrar %s",
              user.getEmailAddress(), permission, registrarId));
    }
  }

  protected void postHandler(User user) {
    throw new UnsupportedOperationException("Console API POST handler not implemented");
  }

  protected void getHandler(User user) {
    throw new UnsupportedOperationException("Console API GET handler not implemented");
  }

  protected void deleteHandler(User user) {
    throw new UnsupportedOperationException("Console API DELETE handler not implemented");
  }

  protected void setFailedResponse(String message, int code) {
    consoleApiParams.response().setStatus(code);
    consoleApiParams.response().setPayload(message);
  }

  private boolean verifyXSRF(User user) {
    Optional<Cookie> maybeCookie =
        Arrays.stream(consoleApiParams.request().getCookies())
            .filter(c -> XsrfTokenManager.X_CSRF_TOKEN.equals(c.getName()))
            .findFirst();
    if (maybeCookie.isEmpty()
        || !consoleApiParams
            .xsrfTokenManager()
            .validateToken(user.getEmailAddress(), maybeCookie.get().getValue())) {
      consoleApiParams.response().setStatus(SC_UNAUTHORIZED);
      return false;
    }
    return true;
  }

  private Map<String, Object> expandRegistrarWithContacts(
      ImmutableSet<RegistrarPoc> contacts, Registrar registrar) {

    ImmutableSet<Map<String, Object>> expandedContacts =
        contacts.stream()
            .map(RegistrarPoc::toDiffableFieldMap)
            // Note: per the javadoc, toDiffableFieldMap includes sensitive data, but we don't want
            // to display it here
            .peek(
                map -> {
                  map.remove("registryLockPasswordHash");
                  map.remove("registryLockPasswordSalt");
                })
            .collect(toImmutableSet());

    Map<String, Object> registrarDiffMap = registrar.toDiffableFieldMap();
    Stream.of("passwordHash", "salt") // fields to remove from final diff
        .forEach(registrarDiffMap::remove);

    // Use LinkedHashMap here to preserve ordering; null values mean we can't use ImmutableMap.
    LinkedHashMap<String, Object> result = new LinkedHashMap<>(registrarDiffMap);
    result.put("contacts", expandedContacts);
    return result;
  }

  protected void sendExternalUpdates(
      Map<?, ?> diffs, Registrar registrar, ImmutableSet<RegistrarPoc> contacts) {

    if (!consoleApiParams.sendEmailUtils().hasRecipients() && contacts.isEmpty()) {
      return;
    }

    if (!RegistryEnvironment.UNITTEST.equals(RegistryEnvironment.get())
        && cloudTasksUtils != null) {
      // Enqueues a sync registrar sheet task if enqueuing is not triggered by console tests and
      // there's an update besides the lastUpdateTime
      cloudTasksUtils.enqueue(
          SyncRegistrarsSheetAction.QUEUE,
          cloudTasksUtils.createTask(
              SyncRegistrarsSheetAction.class, POST, ImmutableMultimap.of()));
    }

    String environment = Ascii.toLowerCase(String.valueOf(RegistryEnvironment.get()));
    consoleApiParams
        .sendEmailUtils()
        .sendEmail(
            String.format(
                "Registrar %s (%s) updated in registry %s environment",
                registrar.getRegistrarName(), registrar.getRegistrarId(), environment),
            String.format(
                """
                The following changes were made in registry %s environment to the registrar %s by\
                 %s:

                %s""",
                environment,
                registrar.getRegistrarId(),
                consoleApiParams.authResult().userIdForLogging(),
                DiffUtils.prettyPrintDiffedMap(diffs, null)),
            contacts.stream()
                .filter(c -> c.getTypes().contains(RegistrarPocBase.Type.ADMIN))
                .map(RegistrarPoc::getEmailAddress)
                .collect(toImmutableList()));
  }

  /**
   * Determines if any changes were made to the registrar besides the lastUpdateTime, and if so,
   * sends an email with a diff of the changes to the configured notification email address and all
   * contact addresses and enqueues a task to re-sync the registrar sheet.
   */
  protected void sendExternalUpdatesIfNecessary(EmailInfo emailInfo) {
    ImmutableSet<RegistrarPoc> existingContacts = emailInfo.contacts();
    Registrar existingRegistrar = emailInfo.registrar();

    Map<?, ?> diffs =
        DiffUtils.deepDiff(
            expandRegistrarWithContacts(existingContacts, existingRegistrar),
            expandRegistrarWithContacts(emailInfo.updatedContacts(), emailInfo.updatedRegistrar()),
            true);

    @SuppressWarnings("unchecked")
    Set<String> changedKeys = (Set<String>) diffs.keySet();
    if (Sets.difference(changedKeys, ImmutableSet.of("lastUpdateTime")).isEmpty()) {
      return;
    }

    sendExternalUpdates(diffs, existingRegistrar, existingContacts);
  }

  protected record EmailInfo(
      Registrar registrar,
      Registrar updatedRegistrar,
      ImmutableSet<RegistrarPoc> contacts,
      ImmutableSet<RegistrarPoc> updatedContacts) {

    public static EmailInfo create(
        Registrar registrar,
        Registrar updatedRegistrar,
        ImmutableSet<RegistrarPoc> contacts,
        ImmutableSet<RegistrarPoc> updatedContacts) {
      return new EmailInfo(registrar, updatedRegistrar, contacts, updatedContacts);
    }
  }

  /** Specialized exception class used for failure when a user doesn't have the right permission. */
  private static class ConsolePermissionForbiddenException extends RuntimeException {
    private ConsolePermissionForbiddenException(String message) {
      super(message);
    }
  }
}
