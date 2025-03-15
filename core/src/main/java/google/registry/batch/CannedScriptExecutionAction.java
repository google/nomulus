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

import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.flogger.FluentLogger;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.UrlConnectionService;
import google.registry.request.UrlConnectionUtils;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

/**
 * Action that executes a canned script specified by the caller.
 *
 * <p>This class provides a hook for invoking hard-coded methods. The main use case is to verify in
 * Sandbox and Production environments new features that depend on environment-specific
 * configurations. For example, the {@code DelegatedCredential}, which requires correct GWorkspace
 * configuration, has been tested this way. Since it is a hassle to add or remove endpoints, we keep
 * this class all the time.
 *
 * <p>This action can be invoked using the Nomulus CLI command: {@code nomulus -e ${env} curl
 * --service BACKEND -X POST -u '/_dr/task/executeCannedScript}'}
 */
@Action(
    service = GaeService.BACKEND,
    path = "/_dr/task/executeCannedScript",
    method = {POST, GET},
    automaticallyPrintOk = true,
    auth = Auth.AUTH_ADMIN)
public class CannedScriptExecutionAction implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject UrlConnectionService urlConnectionService;
  @Inject Response response;

  @Inject
  @Parameter("url")
  String url;

  @Inject
  CannedScriptExecutionAction() {}

  @Override
  public void run() {
    Integer responseCode = null;
    String responseContent = null;
    try {
      logger.atInfo().log("Connecting to: %s", url);
      HttpsURLConnection connection =
          (HttpsURLConnection) urlConnectionService.createConnection(new URL(url));
      responseCode = connection.getResponseCode();
      logger.atInfo().log("Code: %d", responseCode);
      logger.atInfo().log("Headers: %s", connection.getHeaderFields());
      responseContent = new String(UrlConnectionUtils.getResponseBytes(connection), UTF_8);
      logger.atInfo().log("Response: %s", responseContent);
    } catch (Exception e) {
      logger.atWarning().withCause(e).log("Connection to %s failed", url);
      throw new RuntimeException(e);
    } finally {
      if (responseCode != null) {
        response.setStatus(responseCode);
      }
      if (responseContent != null) {
        response.setPayload(responseContent);
      }
    }
  }
}
