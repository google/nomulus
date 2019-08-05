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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.ui.server.registrar.RegistrarConsoleModule.PARAM_CLIENT_ID;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static org.joda.time.DateTimeZone.UTC;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.model.registrar.Registrar;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import java.util.Map;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * Utility class for retrieving existing locks by registrar client ID (if enabled for that client).
 */
final class ExistingRegistryLocksRetriever {

  private static final String LOCKS_PARAM = "locks";
  private static final String FULLY_QUALIFIED_DOMAIN_NAME_PARAM = "fullyQualifiedDomainName";
  private static final String LOCKED_TIME_PARAM = "lockedTime";
  private static final String LOCKED_BY_PARAM = "lockedBy";

  private final AuthenticatedRegistrarAccessor registrarAccessor;

  @Inject
  ExistingRegistryLocksRetriever(AuthenticatedRegistrarAccessor registrarAccessor) {
    this.registrarAccessor = registrarAccessor;
  }

  Map<String, ?> getLockedDomainsMap(String clientId) throws RegistrarAccessDeniedException {
    Registrar registrar = getRegistrarAndVerifyLockAccess(clientId);
    ImmutableList<DummyRegistrarLock> lockedDomains = getLockedDomains(registrar);
    checkArgumentNotNull(lockedDomains);
    return ImmutableMap.of(
        PARAM_CLIENT_ID,
        registrar.getClientId(),
        LOCKS_PARAM,
        lockedDomains.stream().map(DummyRegistrarLock::toMap).collect(toImmutableList()));
  }

  private Registrar getRegistrarAndVerifyLockAccess(String clientId) throws RegistrarAccessDeniedException {
    Registrar registrar = registrarAccessor.getRegistrar(clientId);
    verifyRegistrarLockAccess(registrar);
    return registrar;
  }

  private ImmutableList<DummyRegistrarLock> getLockedDomains(Registrar registrar) {
    checkArgumentNotNull(registrar);
    return ImmutableList.of(
        DummyRegistrarLock.create("test.test", DateTime.now(UTC), "John Doe"),
        DummyRegistrarLock.create("othertest.test", DateTime.now(UTC).minusDays(20), "Jane Doe"),
        DummyRegistrarLock.create("differenttld.tld", DateTime.now(UTC).minusMonths(5), "Foo Bar"));
  }

  private void verifyRegistrarLockAccess(Registrar registrar) {
    checkArgumentNotNull(registrar);
    // TODO: check the actual value once we store it
  }

  @AutoValue
  abstract static class DummyRegistrarLock {
    abstract String fullyQualifiedDomainName();

    abstract DateTime lockedTime();

    abstract String lockedBy();

    static DummyRegistrarLock create(
        String fullyQualifiedDomainName, DateTime lockedTime, String lockedBy) {
      return new AutoValue_ExistingRegistryLocksRetriever_DummyRegistrarLock(
          fullyQualifiedDomainName, lockedTime, lockedBy);
    }

    ImmutableMap<String, ?> toMap() {
      return ImmutableMap.of(
          FULLY_QUALIFIED_DOMAIN_NAME_PARAM,
          fullyQualifiedDomainName(),
          LOCKED_TIME_PARAM,
          lockedTime().toString(),
          LOCKED_BY_PARAM,
          lockedBy());
    }
  }
}
