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
import static google.registry.ui.server.registrar.RegistrarConsoleModule.PARAM_CLIENT_ID;

import com.google.appengine.api.users.User;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.registry.RegistryLockDao;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.request.auth.UserAuthInfo;
import google.registry.schema.domain.RegistryLock;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * Utility class for retrieving existing locks by registrar client ID (if enabled for that client).
 */
class ExistingRegistryLocksRetriever {

  private static final String LOCK_ENABLED_FOR_CONTACT_PARAM = "lockEnabledForContact";
  private static final String EMAIL_PARAM = "email";
  private static final String LOCKS_PARAM = "locks";
  private static final String FULLY_QUALIFIED_DOMAIN_NAME_PARAM = "fullyQualifiedDomainName";
  private static final String LOCKED_TIME_PARAM = "lockedTime";
  private static final String LOCKED_BY_PARAM = "lockedBy";

  private final AuthenticatedRegistrarAccessor registrarAccessor;
  private final AuthResult authResult;

  @Inject
  ExistingRegistryLocksRetriever(
      AuthenticatedRegistrarAccessor registrarAccessor, AuthResult authResult) {
    this.registrarAccessor = registrarAccessor;
    this.authResult = authResult;
  }

  ImmutableMap<String, ?> getLockedDomainsMap(String clientId)
      throws RegistrarAccessDeniedException {
    // Note: admins always have access to the locks page
    checkArgument(authResult.userAuthInfo().isPresent(), "User auth info must be present");
    UserAuthInfo userAuthInfo = authResult.userAuthInfo().get();
    boolean isAdmin = userAuthInfo.isUserAdmin();
    Registrar registrar = getRegistrarAndVerifyLockAccess(clientId, isAdmin);
    User user = userAuthInfo.user();
    boolean isRegistryLockAllowed =
        isAdmin
            || registrar.getContacts().stream()
                .filter(contact -> contact.getEmailAddress().equals(user.getEmail()))
                .findFirst()
                .map(RegistrarContact::isRegistryLockAllowed)
                .orElse(false);
    return ImmutableMap.of(
        LOCK_ENABLED_FOR_CONTACT_PARAM,
        isRegistryLockAllowed,
        EMAIL_PARAM,
        user.getEmail(),
        PARAM_CLIENT_ID,
        registrar.getClientId(),
        LOCKS_PARAM,
        getLockedDomains(registrar));
  }

  private Registrar getRegistrarAndVerifyLockAccess(String clientId, boolean isAdmin)
      throws RegistrarAccessDeniedException {
    Registrar registrar = registrarAccessor.getRegistrar(clientId);
    checkArgument(
        isAdmin || registrar.isRegistryLockAllowed(),
        "Registry lock not allowed for this registrar");
    return registrar;
  }

  private ImmutableList<ImmutableMap<String, ?>> getLockedDomains(Registrar registrar) {
    ImmutableList<RegistryLock> locks =
        RegistryLockDao.getByRegistrarId(registrar.getClientId()).stream()
            .filter(RegistryLock::isVerified)
            .collect(toImmutableList());
    return locks.stream().map(this::lockToMap).collect(toImmutableList());
  }

  private ImmutableMap<String, ?> lockToMap(RegistryLock lock) {
    return ImmutableMap.of(
        FULLY_QUALIFIED_DOMAIN_NAME_PARAM,
        lock.getDomainName(),
        LOCKED_TIME_PARAM,
        lock.getCompletionTimestamp().map(DateTime::toString).orElse(""),
        LOCKED_BY_PARAM,
        lock.isSuperuser() ? "admin" : lock.getRegistrarPocId());
  }
}
