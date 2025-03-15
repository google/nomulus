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

package google.registry.groups;

import static google.registry.util.CollectionUtils.nullToEmpty;
import static jakarta.servlet.http.HttpServletResponse.SC_CONFLICT;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.directory.Directory;
import com.google.api.services.directory.model.Group;
import com.google.api.services.directory.model.Member;
import com.google.api.services.directory.model.Members;
import com.google.api.services.groupssettings.Groupssettings;
import com.google.api.services.groupssettings.model.Groups;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Set;

/**
 * Class encapsulating methods to access Google Groups API.
 */
public class DirectoryGroupsConnection implements GroupsConnection {

  // NOTE: These error message strings were determined empirically. The API documentation contains
  // no mention of what happens in error conditions. Here be dragons.
  private static final String GROUP_NOT_FOUND_MSG = "Resource Not Found: groupKey";
  private static final String MEMBER_NOT_FOUND_MSG = "Resource Not Found: memberKey";
  private static final String MEMBER_ALREADY_EXISTS_MSG = "Member already exists.";

  /**
   * All possible errors from {@link Directory.Members#get} when an email doesn't belong to a group.
   *
   * <p>See {@link #isMemberOfGroup} for details.
   *
   * <p>TODO(b/119220829): remove once we transition to using hasMember
   *
   * <p>TODO(b/119221854): update error messages if and when they change
   */
  private static final ImmutableSet<String> ERROR_MESSAGES_MEMBER_NOT_FOUND =
      ImmutableSet.of(
          // The given email corresponds to an actual account, but isn't part of this group
          "Resource Not Found: memberKey",
          // There's no account corresponding to this email
          "Missing required field: memberKey");

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Groups defaultGroupPermissions = getDefaultGroupPermissions();

  @VisibleForTesting
  static Groups getDefaultGroupPermissions() {
    Groups permissions = new Groups();
    permissions.setAllowExternalMembers(Boolean.TRUE.toString());
    permissions.setWhoCanPostMessage("ALL_MANAGERS_CAN_POST");
    permissions.setWhoCanViewGroup("ALL_MANAGERS_CAN_VIEW");
    permissions.setWhoCanViewMembership("ALL_MANAGERS_CAN_VIEW");
    permissions.setWhoCanJoin("INVITED_CAN_JOIN");
    permissions.setWhoCanInvite("ALL_MANAGERS_CAN_INVITE");
    return permissions;
  }

  @Inject Directory directory;
  @Inject Groupssettings groupsSettings;
  @Inject @Config("gSuiteAdminAccountEmailAddress") String gSuiteAdminAccountEmailAddress;
  @Inject DirectoryGroupsConnection() {}

  @Override
  public void addMemberToGroup(String groupKey, String email, Role role) throws IOException {
    // Documentation for this API call:
    // https://developers.google.com/admin-sdk/directory/v1/reference/members/insert
    Member member = new Member();
    member.setEmail(email);
    member.setRole(role.toString());
    try {
      directory.members().insert(groupKey, member).execute();
    } catch (GoogleJsonResponseException e) {
      // If the member is already in the group, ignore the error, get the existing member, and
      // return it.
      GoogleJsonError err = e.getDetails();
      if (err == null) {
        throw e;
      } else if (err.getCode() == SC_NOT_FOUND && err.getMessage().equals(GROUP_NOT_FOUND_MSG)) {
        logger.atInfo().withCause(e).log(
            "Creating group %s during addition of member %s because the group doesn't exist.",
            groupKey, email);
        createGroup(groupKey);
        addMemberToGroup(groupKey, email, role);
      } else if (err.getCode() == SC_NOT_FOUND && err.getMessage().equals(MEMBER_NOT_FOUND_MSG)) {
        throw new RuntimeException(String.format(
            "Adding member %s to group %s failed because the member wasn't found.",
            email,
            groupKey), e);
      } else if (err.getCode() == SC_CONFLICT
          && err.getMessage().equals(MEMBER_ALREADY_EXISTS_MSG)) {
        // This error case usually happens when an email address is already a member of the gorup,
        // but it is bouncing incoming emails. It won't show up in the members list API call, but
        // will throw a "Member already exists" error message if you attempt to add it again. The
        // correct thing to do is log an info message when this happens and then ignore it.
        logger.atInfo().withCause(e).log(
            "Could not add email %s to group %s because it is already a member "
                + "(likely because the email address is bouncing incoming messages).",
            email, groupKey);
      } else {
        throw e;
      }
    }
  }

  @Override
  public void removeMemberFromGroup(String groupKey, String email) throws IOException {
    // Documentation for this API call:
    // https://developers.google.com/admin-sdk/directory/v1/reference/members/delete
    directory.members().delete(groupKey, email).execute();
  }

  @Override
  public Set<String> getMembersOfGroup(String groupKey) throws IOException {
    // Documentation for this API call:
    // https://developers.google.com/admin-sdk/directory/v1/reference/members/list
    try {
      ImmutableSet.Builder<String> allMembers = new ImmutableSet.Builder<>();
      Directory.Members.List listRequest =
          directory.members().list(groupKey).setRoles(Role.MEMBER.toString());
      do {
        Members currentPage = listRequest.execute();
        for (Member member : nullToEmpty(currentPage.getMembers())) {
          allMembers.add(member.getEmail());
        }
        listRequest.setPageToken(currentPage.getNextPageToken());
      } while (!Strings.isNullOrEmpty(listRequest.getPageToken()));
      return allMembers.build();
    } catch (GoogleJsonResponseException e) {
      if (e.getDetails() != null
          && e.getDetails().getCode() == SC_NOT_FOUND
          && e.getDetails().getMessage().equals(GROUP_NOT_FOUND_MSG)) {
        return ImmutableSet.of();
      } else {
        throw e;
      }
    }
  }

  @Override
  public Group createGroup(String groupKey) throws IOException {
    // Documentation for this API call:
    // https://developers.google.com/admin-sdk/directory/v1/reference/groups/insert
    Group group = new Group();
    group.setEmail(groupKey);
    try {
      Group createdGroup = directory.groups().insert(group).execute();
      addMemberToGroup(groupKey, gSuiteAdminAccountEmailAddress, Role.OWNER);
      groupsSettings.groups().patch(groupKey, defaultGroupPermissions).execute();
      return createdGroup;
    } catch (GoogleJsonResponseException e) {
      // Ignore the error thrown if the group already exists.
      if (e.getDetails() != null
          && e.getDetails().getCode() == SC_CONFLICT
          && e.getDetails().getMessage().equals("Entity already exists.")) {
        logger.atInfo().withCause(e).log(
            "Could not create group %s because it already exists.", groupKey);
        return directory.groups().get(groupKey).execute();
      } else {
        throw e;
      }
    }
  }

  @Override
  public boolean isMemberOfGroup(String memberEmail, String groupKey) {
    // We're using "get" instead of "hasMember" because "hasMember" fails for emails that don't
    // belong to the G-Suite domain.
    //
    // "get" fails for users that aren't part of the group, but it also might fail for other
    // reasons (no access, group doesn't exist etc.).
    // Which error is caused by "user isn't in that group" isn't documented, and was found using
    // trial and error.
    //
    // TODO(b/119221676): transition to using hasMember
    //
    // Documentation for the API of "get":
    // https://developers.google.com/admin-sdk/directory/v1/reference/members/get
    //
    // Documentation for the API of "hasMember":
    // https://developers.google.com/admin-sdk/directory/v1/reference/members/hasMember
    try {
      Directory.Members.Get getRequest = directory.members().get(groupKey, memberEmail);
      Member getReply = getRequest.execute();
      logger.atInfo().log(
          "%s is a member of the group %s. Got reply: %s", memberEmail, groupKey, getReply);
      return true;
    } catch (GoogleJsonResponseException e) {
      if (e.getDetails() != null
          && ERROR_MESSAGES_MEMBER_NOT_FOUND.contains(e.getDetails().getMessage())) {
        // This means the "get" request failed because the email wasn't part of the group.
        // This is expected behavior for any visitor that isn't a support group member.
        logger.atInfo().log(
            "%s isn't a member of the group %s. Got reply %s",
            memberEmail, groupKey, e.getMessage());
        return false;
      }
      // If we got here - we had an unexpected error. Rethrow.
      throw new RuntimeException(
          String.format("Error checking whether %s is in group %s", memberEmail, groupKey), e);
    } catch (IOException e) {
      throw new RuntimeException(
          String.format("Error checking whether %s is in group %s", memberEmail, groupKey), e);
    }
  }
}
