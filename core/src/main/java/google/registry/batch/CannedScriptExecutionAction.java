// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.batch;

import static google.registry.request.Action.Method.POST;

import com.google.common.flogger.FluentLogger;
import google.registry.batch.cannedscript.CannedScripts;
import google.registry.request.Action;
import google.registry.request.auth.Auth;
import javax.inject.Inject;

/**
 * Action that executes a canned script specified by the caller.
 *
 * <p>This class is introduced to help the safe rollout of credential changes. The delegated
 * credentials in particular, benefit from this: they require manual configuration of the peer
 * system in each environment, and may wait hours or even days after deployment until triggered by
 * user activities.
 *
 * <p>This action can be invoked using the Nomulus CLI command: {@code nomulus -e ${env} curl
 * --service BACKEND -X POST -u '/_dr/task/executeCannedScript?script=${script_name}'}
 */
// TODO(b/277239043): remove class after credential changes are rolled out.
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/executeCannedScript",
    method = POST,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class CannedScriptExecutionAction implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject
  CannedScriptExecutionAction() {
    logger.atInfo().log("Received request to run scripts.");
  }

  @Override
  public void run() {
    try {
      CannedScripts.runAllChecks();
      logger.atInfo().log("Finished running scripts.");
    } catch (Throwable t) {
      logger.atWarning().withCause(t).log("Error executing scripts.");
      throw new RuntimeException("Execution failed.");
    }
  }
}
