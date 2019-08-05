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
import static google.registry.security.JsonResponseHelper.Status.ERROR;
import static google.registry.ui.server.registrar.RegistrarConsoleModule.PARAM_CLIENT_ID;

import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.Action.Method;
import google.registry.request.JsonActionRunner;
import google.registry.request.auth.Auth;
import google.registry.security.JsonResponseHelper;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;

/**
 * Admin servlet that allows for updating registrar locks for a particular registrar. Locks /
 * unlocks must be verified separately before they are written permanently.
 *
 * <p>Note: at the moment we have no mechanism for JSON GET/POSTs in the same class or at the same *
 * URL, which is why this is distinct from the {@link RegistryLockGetAction}.
 */
@Action(
    service = Action.Service.DEFAULT,
    path = RegistryLockPostAction.PATH,
    method = Method.POST,
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public final class RegistryLockPostAction implements Runnable, JsonActionRunner.JsonAction {

  public static final String PATH = "/registry-lock-post";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String PARAM_FQDN = "fullyQualifiedDomainName";
  private static final String PARAM_IS_LOCK = "isLock";

  private final ExistingRegistryLocksRetriever existingRegistryLocksRetriever;
  private final JsonActionRunner jsonActionRunner;

  @Inject
  RegistryLockPostAction(
      ExistingRegistryLocksRetriever existingRegistryLocksRetriever,
      JsonActionRunner jsonActionRunner) {
    this.existingRegistryLocksRetriever = existingRegistryLocksRetriever;
    this.jsonActionRunner = jsonActionRunner;
  }

  @Override
  public void run() {
    jsonActionRunner.run(this);
  }

  @Override
  public Map<String, ?> handleJsonRequest(Map<String, ?> input) {
    try {
      checkArgument(input != null, "Malformed JSON");
      String clientId = (String) input.get(PARAM_CLIENT_ID);
      checkArgument(
          !Strings.isNullOrEmpty(clientId), "Missing key for client: %s", PARAM_CLIENT_ID);
      String domainName = (String) input.get(PARAM_FQDN);
      checkArgument(!Strings.isNullOrEmpty(clientId), "Missing key for fqdn: %s", PARAM_FQDN);
      boolean lock = (Boolean) input.get(PARAM_IS_LOCK);
      logger.atInfo().log(
          String.format("Performing action %s to domain %s", lock ? "lock" : "unlock", domainName));
      // TODO: do the lock once we can
      return existingRegistryLocksRetriever.getLockedDomainsMap(clientId);
    } catch (Throwable e) {
      logger.atWarning().withCause(e).log("Failed to lock/unlock domain with input %s", input);
      return JsonResponseHelper.create(
          ERROR, Optional.ofNullable(e.getMessage()).orElse("Unspecified error"));
    }
  }
}
