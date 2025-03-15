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

import com.google.common.flogger.FluentLogger;
import google.registry.groups.GroupsConnection;
import google.registry.groups.GroupsConnection.Role;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;

/** Action that adds or deletes a console user to/from the group that has IAP permissions. */
@Action(
    service = GaeService.TOOLS,
    path = UpdateUserGroupAction.PATH,
    method = POST,
    auth = Auth.AUTH_ADMIN)
public class UpdateUserGroupAction implements Runnable {

  public static final String PATH = "/_dr/admin/updateUserGroup";
  public static final String GROUP_UPDATE_QUEUE = "console-user-group-update";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject GroupsConnection groupsConnection;
  @Inject Response response;

  @Inject
  @Parameter("userEmailAddress")
  String userEmailAddress;

  @Inject
  @Parameter("groupEmailAddress")
  String groupEmailAddress;

  @Inject Mode mode;

  @Inject
  UpdateUserGroupAction() {}

  public enum Mode {
    ADD,
    REMOVE
  }

  @Override
  public void run() {
    logger.atInfo().log(
        "Updating group %s: %s user %s",
        groupEmailAddress, mode == Mode.ADD ? "adding" : "removing", userEmailAddress);
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
