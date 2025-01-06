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

package google.registry.groups;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.directory.model.Group;
import java.io.IOException;
import java.util.Set;

/**
 * Interface for common operations on Groups.
 */
public interface GroupsConnection {

  /** The role of a member in a group. */
  enum Role {
    MEMBER,
    MANAGER,
    OWNER
  }

  /**
   * Adds a member to the specified group with the given role. This function is idempotent; if the
   * member already exists in the group, then it returns normally. If the group doesn't exist, then
   * it is created.
   */
  void addMemberToGroup(String groupKey, String email, Role role) throws IOException;

  /**
   * Removes a member from the specified group, or throws {@link GoogleJsonResponseException} if the
   * member doesn't exist.
   */
  void removeMemberFromGroup(String groupKey, String email) throws IOException;

  /**
   * Returns all of the members of the specified group. Note that it gets members only; not owners
   * or managers. Returns an empty set if the group in question does not exist.
   */
  Set<String> getMembersOfGroup(String groupKey) throws IOException;

  /**
   * Creates a group with the given email address (groupKey) that is open for external members to
   * join, and returns it. This function is idempotent; if the given group already exists, then this
   * function returns as normal without error (and without modifying the existing group in any way,
   * including permissions on who is able to join). The configured admin owner for the Google App is
   * automatically added as an owner.
   */
  Group createGroup(String groupKey) throws IOException;

  /** Checks whether the given email belongs to the "support" group. */
  boolean isMemberOfGroup(String memberEmail, String groupKey);
}
