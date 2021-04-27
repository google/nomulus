// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools.server;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.registry.Registries.assertTldExists;
import static google.registry.request.Action.Method.POST;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import google.registry.model.registry.label.PremiumListDualDao;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import java.util.List;
import javax.inject.Inject;

/**
 * An action that creates a premium list, for use by the {@code nomulus create_premium_list}
 * command.
 */
@Action(
    service = Action.Service.TOOLS,
    path = CreatePremiumListAction.PATH,
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class CreatePremiumListAction extends CreateOrUpdatePremiumListAction {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String OVERRIDE_PARAM = "override";
  public static final String PATH = "/_dr/admin/createPremiumList";

  @Inject @Parameter(OVERRIDE_PARAM) boolean override;

  @Inject CreatePremiumListAction() {}

  @Override
  protected void save() {
    checkArgument(
        !PremiumListDualDao.exists(name), "A premium list of this name already exists: %s", name);
    if (!override) {
      assertTldExists(
          name,
          "Premium names must match the name of the TLD they are intended to be used on"
              + " (unless --override is specified), yet TLD %s does not exist");
    }
    logger.atInfo().log("Saving premium list for TLD %s", name);
    logInputData();
    List<String> inputDataPreProcessed =
        Splitter.on('\n').omitEmptyStrings().splitToList(inputData);
    PremiumListDualDao.save(name, inputDataPreProcessed);
    String message =
        String.format("Saved premium list %s with %d entries", name, inputDataPreProcessed.size());
    logger.atInfo().log(message);
    response.setPayload(ImmutableMap.of("status", "success", "message", message));
  }
}
