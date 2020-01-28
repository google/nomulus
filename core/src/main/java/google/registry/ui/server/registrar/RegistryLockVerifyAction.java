// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.ui.server.SoyTemplateUtils.CSS_RENAMING_MAP_SUPPLIER;

import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.flogger.FluentLogger;
import com.google.template.soy.tofu.SoyTofu;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.schema.domain.RegistryLock;
import google.registry.tools.DomainLockUtils;
import google.registry.ui.server.SoyTemplateUtils;
import google.registry.ui.soy.registrar.RegistryLockVerificationSoyInfo;
import google.registry.util.Clock;
import java.util.HashMap;
import javax.inject.Inject;

/** Action that allows for verification of registry lock / unlock requests */
@Action(
    service = Action.Service.DEFAULT,
    path = RegistryLockVerifyAction.PATH,
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public final class RegistryLockVerifyAction extends HtmlAction {

  public static final String PATH = "/registry-lock-verify";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final Supplier<SoyTofu> TOFU_SUPPLIER =
      SoyTemplateUtils.createTofuSupplier(
          google.registry.ui.soy.ConsoleSoyInfo.getInstance(),
          google.registry.ui.soy.AnalyticsSoyInfo.getInstance(),
          google.registry.ui.soy.registrar.RegistryLockVerificationSoyInfo.getInstance());

  private final Clock clock;
  private final String lockVerificationCode;
  private final Boolean isLock;

  @Inject
  public RegistryLockVerifyAction(
      Clock clock,
      @Parameter("lockVerificationCode") String lockVerificationCode,
      @Parameter("isLock") Boolean isLock) {
    this.clock = clock;
    this.lockVerificationCode = lockVerificationCode;
    this.isLock = isLock;
  }

  @Override
  public void runAfterLogin(HashMap<String, Object> data) {
    try {
      boolean isAdmin = authResult.userAuthInfo().get().isUserAdmin();
      final RegistryLock resultLock;
      if (isLock) {
        resultLock = DomainLockUtils.verifyAndApplyLock(lockVerificationCode, isAdmin, clock);
      } else {
        resultLock = DomainLockUtils.verifyAndApplyUnlock(lockVerificationCode, isAdmin, clock);
      }
      data.put("isLock", isLock);
      data.put("success", true);
      data.put("fullyQualifiedDomainName", resultLock.getDomainName());
    } catch (Throwable t) {
      logger.atWarning().withCause(t).log(
          "Error when verifying verification code %s", lockVerificationCode);
      data.put("success", false);
      data.put("errorMessage", Throwables.getRootCause(t).getMessage());
    }
    response.setPayload(
        TOFU_SUPPLIER
            .get()
            .newRenderer(RegistryLockVerificationSoyInfo.VERIFICATION_PAGE)
            .setCssRenamingMap(CSS_RENAMING_MAP_SUPPLIER.get())
            .setData(data)
            .render());
  }

  @Override
  public String getPath() {
    return PATH;
  }
}
