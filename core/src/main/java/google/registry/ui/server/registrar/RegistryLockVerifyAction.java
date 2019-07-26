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

import static com.google.common.net.HttpHeaders.LOCATION;
import static com.google.common.net.HttpHeaders.X_FRAME_OPTIONS;
import static javax.servlet.http.HttpServletResponse.SC_MOVED_TEMPORARILY;

import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserService;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import com.google.template.soy.shared.SoyCssRenamingMap;
import com.google.template.soy.tofu.SoyTofu;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthResult;
import google.registry.ui.server.SoyTemplateUtils;
import google.registry.ui.soy.registrar.RegistryLockVerificationSoyInfo;
import java.util.Map;
import java.util.UUID;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;

/** Servlet that allows for verification of registry lock / unlock requests */
@Action(
    service = Action.Service.DEFAULT,
    path = RegistryLockVerifyAction.PATH,
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public final class RegistryLockVerifyAction implements Runnable {

  public static final String PATH = "/registry-lock-verify";

  private static final Supplier<SoyTofu> TOFU_SUPPLIER =
      SoyTemplateUtils.createTofuSupplier(
          google.registry.ui.soy.ConsoleSoyInfo.getInstance(),
          google.registry.ui.soy.AnalyticsSoyInfo.getInstance(),
          google.registry.ui.soy.registrar.RegistryLockVerificationSoyInfo.getInstance());

  @VisibleForTesting
  public static final Supplier<SoyCssRenamingMap> CSS_RENAMING_MAP_SUPPLIER =
      SoyTemplateUtils.createCssRenamingMapSupplier(
          Resources.getResource("google/registry/ui/css/registrar_bin.css.js"),
          Resources.getResource("google/registry/ui/css/registrar_dbg.css.js"));

  private final HttpServletRequest req;
  private final Response response;
  private final UserService userService;
  @VisibleForTesting AuthResult authResult;
  private final Map<String, Object> analyticsConfig;
  private final String logoFilename;
  private final String productName;
  @VisibleForTesting UUID lockId;

  @Inject
  public RegistryLockVerifyAction(
      HttpServletRequest req,
      Response response,
      UserService userService,
      AuthResult authResult,
      @Config("analyticsConfig") Map<String, Object> analyticsConfig,
      @Config("logoFilename") String logoFilename,
      @Config("productName") String productName,
      @Parameter("lockId") UUID lockId) {
    this.req = req;
    this.response = response;
    this.userService = userService;
    this.authResult = authResult;
    this.analyticsConfig = analyticsConfig;
    this.logoFilename = logoFilename;
    this.productName = productName;
    this.lockId = lockId;
  }

  @Override
  public void run() {
    response.setHeader(X_FRAME_OPTIONS, "SAMEORIGIN"); // Disallow iframing.
    response.setHeader("X-Ui-Compatible", "IE=edge"); // Ask IE not to be silly.

    if (!authResult.userAuthInfo().isPresent()) {
      response.setStatus(SC_MOVED_TEMPORARILY);
      String location;
      try {
        location = userService.createLoginURL(req.getRequestURI());
      } catch (IllegalArgumentException e) {
        // UserServiceImpl.createLoginURL() throws IllegalArgumentException if underlying API call
        // returns an error code of NOT_ALLOWED. createLoginURL() assumes that the error is caused
        // by an invalid URL. But in fact, the error can also occur if UserService doesn't have any
        // user information, which happens when the request has been authenticated as internal. In
        // this case, we want to avoid dying before we can send the redirect, so just redirect to
        // the root path.
        location = "/";
      }
      response.setHeader(LOCATION, location);
      return;
    }
    User user = authResult.userAuthInfo().get().user();

    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
    builder.put("logoFilename", logoFilename);
    builder.put("productName", productName);
    builder.put("username", user.getNickname());
    builder.put("logoutUrl", userService.createLogoutURL(PATH));
    builder.put("analyticsConfig", analyticsConfig);

    // TODO: actually do the lock / unlock verification
    builder.put("isLock", true);
    builder.put("fullyQualifiedDomainName", "foo.app");

    // random UUIDs for testing the UI
    if (UUID.fromString("d08392e8-54f9-4b55-825f-85391ed81839").equals(lockId)) {
      builder.put("success", true);
    } else if (UUID.fromString("6b4b3976-6b97-466a-980b-00ac794b9bf3").equals(lockId)) {
      builder.put("success", false);
      builder.put("errorMessage", "The pending lock has expired. Please try again.");
    } else {
      builder.put("success", false);
      builder.put("errorMessage", "Unknown lock ID");
    }
    response.setPayload(
        TOFU_SUPPLIER
            .get()
            .newRenderer(RegistryLockVerificationSoyInfo.VERIFICATION_PAGE)
            .setCssRenamingMap(CSS_RENAMING_MAP_SUPPLIER.get())
            .setData(builder.build())
            .render());
  }
}
