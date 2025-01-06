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

package google.registry.tools.server;

import static com.google.common.truth.Truth.assertThat;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import google.registry.groups.DirectoryGroupsConnection;
import google.registry.groups.GroupsConnection.Role;
import google.registry.testing.FakeResponse;
import google.registry.tools.server.UpdateUserGroupAction.Mode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link google.registry.tools.server.UpdateUserGroupAction}. */
class UpdateUserGroupActionTest {

  private final DirectoryGroupsConnection connection = mock(DirectoryGroupsConnection.class);
  private final FakeResponse response = new FakeResponse();
  private final UpdateUserGroupAction action = new UpdateUserGroupAction();
  private final String userEmailAddress = "user@example.com";
  private final String groupEmailAddress = "group@example.com";

  @BeforeEach
  void beforeEach() {
    action.groupsConnection = connection;
    action.response = response;
    action.userEmailAddress = userEmailAddress;
    action.groupEmailAddress = groupEmailAddress;
    action.mode = Mode.ADD;
  }

  @Test
  void testSuccess_addMember() throws Exception {
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(connection).addMemberToGroup(groupEmailAddress, userEmailAddress, Role.MEMBER);
    verifyNoMoreInteractions(connection);
  }

  @Test
  void testSuccess_removeMember() throws Exception {
    action.mode = Mode.REMOVE;
    when(connection.isMemberOfGroup(userEmailAddress, groupEmailAddress)).thenReturn(true);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(connection).isMemberOfGroup(userEmailAddress, groupEmailAddress);
    verify(connection).removeMemberFromGroup(groupEmailAddress, userEmailAddress);
    verifyNoMoreInteractions(connection);
  }

  @Test
  void testSuccess_removeMember_notAMember() throws Exception {
    action.mode = Mode.REMOVE;
    when(connection.isMemberOfGroup(userEmailAddress, groupEmailAddress)).thenReturn(false);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(connection).isMemberOfGroup(userEmailAddress, groupEmailAddress);
    verifyNoMoreInteractions(connection);
  }
}
