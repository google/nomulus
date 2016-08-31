// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static google.registry.model.ofy.ObjectifyService.ofy;

import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserService;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.util.FormattingLogger;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/** HTTP session management helper class. */
@Immutable
public class SessionUtils {

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

  public static final String CLIENT_ID_ATTRIBUTE = "clientId";

  private final UserService userService;

  @Inject
  public SessionUtils(UserService userService) {
    this.userService = checkNotNull(userService);
  }

  /**
   * Checks GAE user has access to Registrar Console.
   *
   * <p>This routine will first check the HTTP session (creating one if it doesn't exist) for the
   * {@code clientId} attribute:
   *
   * <ul>
   * <li>If it does not exist, then we will attempt to guess the {@link Registrar} with which the
   * user's GAIA ID is associated. The {@code clientId} of the first matching {@code Registrar} will
   * then be stored to the the HTTP session.
   * <li>If it does exist, then we'll fetch the Registrar from the datastore to make sure access
   * wasn't revoked. This should only cost one memcache read.
   * </ul>
   *
   * <p><b>Note:</b> You must ensure the user has logged in before calling this method, for example
   * by setting {@code @Action(requireLogin = true)}.
   *
   * @return {@code false} if user does not have access, in which case the caller should write an
   *     error response and abort the request.
   */
  @CheckReturnValue
  public boolean checkRegistrarConsoleLogin(HttpServletRequest req) {
    HttpSession session = req.getSession();
    User user = userService.getCurrentUser();
    checkState(user != null, "No logged in user found");
    String clientId = (String) session.getAttribute(CLIENT_ID_ATTRIBUTE);
    if (clientId == null) {
      Optional<Registrar> registrar = guessRegistrar(user.getUserId());
      if (!registrar.isPresent()) {
        logger.infofmt("User not associated with any Registrar: %s (%s)",
            user.getUserId(), user.getEmail());
        return false;
      }
      verify(hasAccessToRegistrar(registrar.get(), user.getUserId()));
      session.setAttribute(CLIENT_ID_ATTRIBUTE, registrar.get().getClientIdentifier());
    } else {
      if (!hasAccessToRegistrar(clientId, user.getUserId())) {
        logger.infofmt("Registrar Console access revoked: %s for %s (%s)",
            clientId, user.getEmail(), user.getUserId());
        session.invalidate();
        return false;
      }
    }
    return true;
  }

  /**
   * Returns {@link Registrar} clientId associated with HTTP session.
   *
   * @throws IllegalStateException if you forgot to call {@link #checkRegistrarConsoleLogin}.
   */
  @CheckReturnValue
  public String getRegistrarClientId(HttpServletRequest req) {
    String clientId = (String) req.getSession().getAttribute(CLIENT_ID_ATTRIBUTE);
    checkState(clientId != null, "You forgot to call checkRegistrarConsoleLogin()");
    return clientId;
  }

  /** @see UserService#isUserLoggedIn() */
  public boolean isLoggedIn() {
    return userService.isUserLoggedIn();
  }

  /** Returns first {@link Registrar} that {@code gaeUserId} is authorized to administer. */
  private static Optional<Registrar> guessRegistrar(String gaeUserId) {
    RegistrarContact contact = ofy().load()
        .type(RegistrarContact.class)
        .filter("gaeUserId", gaeUserId)
        .first().now();
    if (contact == null) {
      return Optional.absent();
    }
    return Optional.of(ofy().load().key(contact.getParent()).safe());
  }

  /** @see #hasAccessToRegistrar(Registrar, String) */
  private static boolean hasAccessToRegistrar(String clientId, final String gaeUserId) {
    Registrar registrar = Registrar.loadByClientId(clientId);
    if (registrar == null) {
      logger.warningfmt("Registrar '%s' disappeared from the datastore!", clientId);
      return false;
    }
    return hasAccessToRegistrar(registrar, gaeUserId);
  }

  /** Returns {@code true} if {@code gaeUserId} is listed in contacts. */
  private static boolean hasAccessToRegistrar(Registrar registrar, final String gaeUserId) {
    return FluentIterable
        .from(registrar.getContacts())
        .anyMatch(new Predicate<RegistrarContact>() {
          @Override
          public boolean apply(@Nonnull RegistrarContact contact) {
            return gaeUserId.equals(contact.getGaeUserId());
          }});
  }
}
