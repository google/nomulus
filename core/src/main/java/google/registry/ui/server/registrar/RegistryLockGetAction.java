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
import static com.google.common.net.HttpHeaders.X_FRAME_OPTIONS;
import static google.registry.security.JsonResponseHelper.Status.SUCCESS;
import static google.registry.ui.server.registrar.RegistrarConsoleModule.PARAM_CLIENT_ID;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import com.google.gson.Gson;
import google.registry.request.Action;
import google.registry.request.Action.Method;
import google.registry.request.Parameter;
import google.registry.request.RequestMethod;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.security.JsonResponseHelper;
import java.util.Optional;
import javax.inject.Inject;

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
public final class RegistryLockGetAction implements Runnable {

  public static final String PATH = "/registry-lock-get";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Gson GSON = new Gson();

  @VisibleForTesting Method method;
  private final Response response;
  @VisibleForTesting AuthResult authResult;
  private final ExistingRegistryLocksRetriever existingRegistryLocksRetriever;
  @VisibleForTesting Optional<String> paramClientId;

  @Inject
  RegistryLockGetAction(
      @RequestMethod Method method,
      Response response,
      AuthResult authResult,
      ExistingRegistryLocksRetriever existingRegistryLocksRetriever,
      @Parameter(PARAM_CLIENT_ID) Optional<String> paramClientId) {
    this.method = method;
    this.response = response;
    this.authResult = authResult;
    this.existingRegistryLocksRetriever = existingRegistryLocksRetriever;
    this.paramClientId = paramClientId;
  }

  @Override
  public void run() {
    checkArgument(Method.GET.equals(method), "Only GET requests allowed");
    checkArgument(authResult.userAuthInfo().isPresent(), "User auth info must be present");
    checkArgument(paramClientId.isPresent(), "clientId must be present");
    response.setContentType(MediaType.JSON_UTF_8);
    response.setHeader(X_FRAME_OPTIONS, "SAMEORIGIN"); // Disallow iframing.
    response.setHeader("X-Ui-Compatible", "IE=edge"); // Ask IE not to be silly.

    try {
      ImmutableMap<String, Object> resultMap =
          existingRegistryLocksRetriever.getLockedDomainsMap(paramClientId.get());
      ImmutableMap<String, ?> payload =
          JsonResponseHelper.create(SUCCESS, "Successful locks retrieval", resultMap);
      response.setPayload(GSON.toJson(payload));
    } catch (RegistrarAccessDeniedException e) {
      logger.atWarning().withCause(e).log(
          "User %s doesn't have access to registrar console.", authResult.userIdForLogging());
      response.setStatus(SC_FORBIDDEN);
    }
  }
}
