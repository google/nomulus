// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.module;

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.flogger.FluentLogger;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.Action.GkeService;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletResponse;

public class ReadinessProbeAction implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final HttpServletResponse rsp;

  public ReadinessProbeAction(HttpServletResponse rsp) {
    this.rsp = rsp;
  }

  /**
   * Executes the readiness check.
   *
   * <p>Performs a simple database query and sets the HTTP response status to OK (200) upon
   * successful completion. Throws a runtime exception if the database query fails.
   */
  @Override
  public final void run() {
    logger.atInfo().log("Performing readiness check database query...");
    try {
      tm().transact(() -> tm().query("SELECT version()", Void.class));
      rsp.setStatus(SC_OK);
      logger.atInfo().log("Readiness check successful.");
    } catch (Exception e) {
      logger.atWarning().withCause(e).log("Readiness check failed:");
      throw new RuntimeException("Readiness check failed during database query", e);
    }
  }

  @Action(
      service = GaeService.DEFAULT,
      gkeService = GkeService.CONSOLE,
      path = ReadinessProbeConsoleAction.PATH,
      auth = Auth.AUTH_PUBLIC)
  public static class ReadinessProbeConsoleAction extends ReadinessProbeAction {
    public static final String PATH = "/ready/console";

    @Inject
    public ReadinessProbeConsoleAction(HttpServletResponse rsp) {
      super(rsp);
    }
  }

  @Action(
      service = GaeService.PUBAPI,
      gkeService = GkeService.PUBAPI,
      path = ReadinessProbeActionPubApi.PATH,
      auth = Auth.AUTH_PUBLIC)
  public static class ReadinessProbeActionPubApi extends ReadinessProbeAction {
    public static final String PATH = "/ready/pubapi";

    @Inject
    public ReadinessProbeActionPubApi(HttpServletResponse rsp) {
      super(rsp);
    }
  }

  @Action(
      service = GaeService.DEFAULT,
      gkeService = GkeService.FRONTEND,
      path = ReadinessProbeActionFrontend.PATH,
      auth = Auth.AUTH_PUBLIC)
  public static final class ReadinessProbeActionFrontend extends ReadinessProbeAction {
    public static final String PATH = "/ready/frontend";

    @Inject
    public ReadinessProbeActionFrontend(HttpServletResponse rsp) {
      super(rsp);
    }
  }
}
