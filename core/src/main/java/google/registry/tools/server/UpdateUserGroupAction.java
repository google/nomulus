// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.request.Action.Method.POST;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

import com.google.common.base.Ascii;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.groups.GroupsConnection;
import google.registry.groups.GroupsConnection.Role;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import java.util.Optional;
import javax.inject.Inject;

/** Action that adds or deletes a console user to/from the group that has IAP permissions. */
@Action(
    service = Action.Service.TOOLS,
    path = UpdateUserGroupAction.PATH,
    method = POST,
    auth = Auth.AUTH_API_ADMIN)
public class UpdateUserGroupAction implements Runnable {

  public static final String PATH = "/_dr/admin/updateUserGroup";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject GroupsConnection groupsConnection;
  @Inject Response response;

  @Inject
  @Parameter("userEmailAddress")
  String userEmailAddress;

  @Inject
  @Config("gSuiteConsoleUserGroupEmailAddress")
  Optional<String> mayBeGroupEmailAddress;

  @Inject Mode mode;

  @Inject
  UpdateUserGroupAction() {}

  enum Mode {
    ADD,
    REMOVE
  }

  @Override
  public void run() {
    String groupEmailAddress = mayBeGroupEmailAddress.orElse(null);
    if (groupEmailAddress == null) {
      String message = "No console user group email configured, skipping update";
      logger.atInfo().log(message);
      response.setPayload(message);
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      return;
    }
    logger.atInfo().log(
        "Updating group %s: %sing user %s",
        groupEmailAddress, Ascii.toLowerCase(mode.toString()), userEmailAddress);
    try {
      if (mode == Mode.ADD) {
        // The group will be created if it does not exist.
        groupsConnection.addMemberToGroup(groupEmailAddress, userEmailAddress, Role.MEMBER);
      } else {
        if (groupsConnection.isMemberOfGroup(userEmailAddress, groupEmailAddress)) {
          groupsConnection.removeMemberFromGroup(groupEmailAddress, userEmailAddress);
        } else {
          logger.atInfo().log(
              "Ignoring request to remove non-member %s from group %s",
              userEmailAddress, groupEmailAddress);
        }
      }
    } catch (Exception e) {
      throw new RuntimeException("Cannot update group", e);
    }
  }
}
