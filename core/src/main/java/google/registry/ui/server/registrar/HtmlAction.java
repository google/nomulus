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

import static com.google.common.net.HttpHeaders.X_FRAME_OPTIONS;
import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;

import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.console.User;
import google.registry.request.Action;
import google.registry.request.RequestMethod;
import google.registry.request.Response;
import google.registry.request.auth.AuthResult;
import google.registry.security.XsrfTokenManager;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;

/**
 * Handles some of the nitty-gritty of responding to requests that should return HTML, including
 * login, redirects, analytics, and some headers.
 *
 * <p>If the user is not logged in, this will redirect to the login URL.
 */
public abstract class HtmlAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject HttpServletRequest req;
  @Inject Response response;
  @Inject XsrfTokenManager xsrfTokenManager;
  @Inject AuthResult authResult;
  @Inject @RequestMethod Action.Method method;

  @Inject
  @Config("logoFilename")
  String logoFilename;

  @Inject
  @Config("productName")
  String productName;

  @Inject
  @Config("analyticsConfig")
  Map<String, Object> analyticsConfig;

  @Override
  public void run() {
    response.setHeader(X_FRAME_OPTIONS, "SAMEORIGIN"); // Disallow iframing.
    response.setHeader("X-Ui-Compatible", "IE=edge"); // Ask IE not to be silly.

    if (authResult.user().isEmpty()) {
      response.setStatus(SC_UNAUTHORIZED);
      return;
    }
    response.setContentType(MediaType.HTML_UTF_8);

    User user = authResult.user().get();
    // Using HashMap to allow null values
    HashMap<String, Object> data = new HashMap<>();
    data.put("logoFilename", logoFilename);
    data.put("productName", productName);
    data.put("username", user.getEmailAddress());
    data.put("logoutUrl", "/registrar?gcp-iap-mode=CLEAR_LOGIN_COOKIE");
    data.put("analyticsConfig", analyticsConfig);
    data.put("xsrfToken", xsrfTokenManager.generateToken(user.getEmailAddress()));

    logger.atInfo().log(
        "User %s is accessing %s with method %s.",
        authResult.userIdForLogging(), getClass().getName(), method);
    runAfterLogin(data);
  }

  public abstract void runAfterLogin(Map<String, Object> data);

  public abstract String getPath();
}
