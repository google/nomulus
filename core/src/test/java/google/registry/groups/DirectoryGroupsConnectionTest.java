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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.groups.DirectoryGroupsConnection.getDefaultGroupPermissions;
import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.json.Json;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.testing.http.HttpTesting;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.admin.directory.Directory;
import com.google.api.services.admin.directory.model.Group;
import com.google.api.services.admin.directory.model.Member;
import com.google.api.services.admin.directory.model.Members;
import com.google.api.services.groupssettings.Groupssettings;
import com.google.api.services.groupssettings.model.Groups;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.groups.GroupsConnection.Role;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DirectoryGroupsConnection}. */
class DirectoryGroupsConnectionTest {

  private final Directory directory = mock(Directory.class);
  private final Groupssettings groupsSettings = mock(Groupssettings.class);
  private final Directory.Members members = mock(Directory.Members.class);
  private final Directory.Groups directoryGroups = mock(Directory.Groups.class);
  private final Groupssettings.Groups settingsGroups = mock(Groupssettings.Groups.class);
  private final Directory.Members.Insert membersInsert = mock(Directory.Members.Insert.class);
  private final Directory.Groups.Insert groupsInsert = mock(Directory.Groups.Insert.class);
  private final Directory.Groups.Get groupsGet = mock(Directory.Groups.Get.class);
  private final Directory.Members.Get membersGet = mock(Directory.Members.Get.class);
  private final Directory.Members.List membersList = mock(Directory.Members.List.class);
  private final Groupssettings.Groups.Patch groupsSettingsPatch =
      mock(Groupssettings.Groups.Patch.class);

  private DirectoryGroupsConnection connection;
  private Member expectedOwner = new Member();

  @BeforeEach
  void beforeEach() throws Exception {
    when(directory.members()).thenReturn(members);
    when(members.insert(anyString(), any(Member.class))).thenReturn(membersInsert);
    when(members.get(anyString(), anyString())).thenReturn(membersGet);
    when(directory.groups()).thenReturn(directoryGroups);
    when(directoryGroups.insert(any(Group.class))).thenReturn(groupsInsert);
    when(directoryGroups.get(anyString())).thenReturn(groupsGet);
    when(groupsSettings.groups()).thenReturn(settingsGroups);
    when(settingsGroups.patch(anyString(), any(Groups.class))).thenReturn(groupsSettingsPatch);
    connection = new DirectoryGroupsConnection();
    connection.directory = directory;
    connection.groupsSettings = groupsSettings;
    connection.gSuiteAdminAccountEmailAddress = "admin@domain-registry.example";
    expectedOwner.setEmail("admin@domain-registry.example");
    expectedOwner.setRole("OWNER");
  }

  @Test
  void test_addMemberToGroup_succeeds() throws Exception {
    runAddMemberTest();
  }

  @Test
  void test_addMemberToGroup_isIdempotent_whenMemberAlreadyExists() throws Exception {
    Member expected = runAddMemberTest();
    when(membersInsert.execute())
        .thenThrow(makeResponseException(SC_CONFLICT, "Member already exists."));
    when(membersGet.execute()).thenReturn(expected);
    connection.addMemberToGroup("spam@example.com", "jim@example.com", Role.MEMBER);
    verify(members, times(2)).insert("spam@example.com", expected);
  }

  @Test
  void test_addMemberToGroup_handlesExceptionThrownByDirectoryService() throws Exception {
    when(membersInsert.execute()).thenThrow(
        makeResponseException(SC_INTERNAL_SERVER_ERROR, "Could not contact Directory server."));
    assertThrows(GoogleJsonResponseException.class, this::runAddMemberTest);
  }

  @Test
  void test_addMemberToGroup_handlesMemberKeyNotFoundException() throws Exception {
    when(membersInsert.execute()).thenThrow(
        makeResponseException(SC_NOT_FOUND, "Resource Not Found: memberKey"));
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () -> connection.addMemberToGroup("spam@example.com", "jim@example.com", Role.MEMBER));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "Adding member jim@example.com to group spam@example.com "
                + "failed because the member wasn't found.");
  }

  @Test
  void test_addMemberToGroup_createsGroupWhenNonexistent() throws Exception {
    Member expected = new Member();
    expected.setEmail("tim@example.com");
    expected.setRole("MEMBER");
    when(membersInsert.execute())
        .thenThrow(makeResponseException(SC_NOT_FOUND, "Resource Not Found: groupKey"))
        .thenReturn(expected)
        .thenReturn(expectedOwner);
    Group expectedGroup = createExpectedGroup("nonexistentgroup@example.com");
    connection.addMemberToGroup("nonexistentgroup@example.com", "tim@example.com", Role.MEMBER);
    verify(members, times(2)).insert("nonexistentgroup@example.com", expected);
    verify(members).insert("nonexistentgroup@example.com", expectedOwner);
    verify(membersInsert, times(3)).execute();
    verify(directoryGroups).insert(expectedGroup);
    verify(groupsInsert).execute();
    verify(settingsGroups).patch("nonexistentgroup@example.com", getDefaultGroupPermissions());
    verify(groupsSettingsPatch).execute();
  }

  private Group createExpectedGroup(String groupKey) {
    Group group = new Group();
    group.setEmail(groupKey);
    return group;
  }

  @Test
  void test_createGroup_succeeds() throws Exception {
    runCreateGroupTest();
  }

  @Test
  void test_createGroup_isIdempotent_whenGroupAlreadyExists() throws Exception {
    Group expected = runCreateGroupTest();
    when(groupsInsert.execute())
        .thenThrow(makeResponseException(SC_CONFLICT, "Entity already exists."));
    when(groupsGet.execute()).thenReturn(expected);
    assertThat(connection.createGroup("spamlovers@hormel.com")).isEqualTo(expected);
    verify(directoryGroups).get("spamlovers@hormel.com");
    verify(groupsGet).execute();
  }

  @Test
  void test_createGroup_handlesExceptionThrownByDirectoryService() throws Exception {
    when(groupsInsert.execute()).thenThrow(
        makeResponseException(SC_INTERNAL_SERVER_ERROR, "Could not contact Directory server."));
    assertThrows(GoogleJsonResponseException.class, this::runCreateGroupTest);
  }

  @Test
  void test_getMembersOfGroup_succeeds() throws Exception {
    Member member1 = new Member();
    member1.setEmail("john@example.com");
    Member member2 = new Member();
    member2.setEmail("tim@bar.foo");
    Member member3 = new Member();
    member3.setEmail("tank@spam.how");
    Member member4 = new Member();
    member4.setEmail("jank@latte.soy");
    Members page1 = new Members();
    page1.setNextPageToken("page2");
    page1.setMembers(ImmutableList.of(member1, member2));
    Members page2 = new Members();
    page2.setNextPageToken(null);
    page2.setMembers(ImmutableList.of(member3, member4));
    when(membersList.execute()).thenReturn(page1, page2);
    when(membersList.getPageToken()).thenReturn("page2", (String) null);
    when(membersList.setRoles("MEMBER")).thenReturn(membersList);
    when(members.list("contacts@bar.foo")).thenReturn(membersList);

    Set<String> emails = connection.getMembersOfGroup("contacts@bar.foo");
    verify(members).list("contacts@bar.foo");
    verify(membersList).setRoles("MEMBER");
    assertThat(emails).containsExactlyElementsIn(
        ImmutableSet.of("john@example.com", "tim@bar.foo", "tank@spam.how", "jank@latte.soy"));
  }

  @Test
  void test_getMembersOfNonexistentGroup_returnsEmptySet() throws Exception {
    when(members.list("nonexistent_group@fake.notreal")).thenReturn(membersList);
    when(membersList.setRoles("MEMBER")).thenReturn(membersList);
    when(membersList.execute())
        .thenThrow(makeResponseException(SC_NOT_FOUND, "Resource Not Found: groupKey"));
    assertThat(connection.getMembersOfGroup("nonexistent_group@fake.notreal")).isEmpty();
  }

  @Test
  void test_isMemberOfSupportGroup_userInGroup_True() throws Exception {
    when(members.get("support@domain-registry.example", "user@example.com")).thenReturn(membersGet);
    when(membersGet.execute()).thenReturn(new Member());
    assertThat(connection.isMemberOfGroup("user@example.com", "support@domain-registry.example"))
        .isTrue();
  }

  @Test
  void test_isMemberOfSupportGroup_userExistButNotInGroup_returnsFalse() throws Exception {
    when(members.get("support@domain-registry.example", "user@example.com"))
        .thenThrow(makeResponseException(0, "Resource Not Found: memberKey"));
    when(membersGet.execute()).thenReturn(new Member());
    assertThat(connection.isMemberOfGroup("user@example.com", "support@domain-registry.example"))
        .isFalse();
  }

  @Test
  void test_isMemberOfSupportGroup_userDoesntExist_returnsFalse() throws Exception {
    when(members.get("support@domain-registry.example", "user@example.com"))
        .thenThrow(makeResponseException(0, "Missing required field: memberKey"));
    when(membersGet.execute()).thenReturn(new Member());
    assertThat(connection.isMemberOfGroup("user@example.com", "support@domain-registry.example"))
        .isFalse();
  }

  @Test
  void test_isMemberOfSupportGroup_otherError_throws() throws Exception {
    when(members.get("support@domain-registry.example", "user@example.com"))
        .thenThrow(makeResponseException(0, "some error"));
    when(membersGet.execute()).thenReturn(new Member());
    RuntimeException e =
        assertThrows(
            RuntimeException.class,
            () ->
                connection.isMemberOfGroup("user@example.com", "support@domain-registry.example"));
    assertThat(e).hasCauseThat().isInstanceOf(GoogleJsonResponseException.class);
  }

  /** Runs a full test to add a member to the group and returns the expected Member. */
  private Member runAddMemberTest() throws Exception {
    connection.addMemberToGroup("spam@example.com", "jim@example.com", Role.MEMBER);
    Member expected = new Member();
    expected.setEmail("jim@example.com");
    expected.setRole("MEMBER");
    verify(members).insert("spam@example.com", expected);
    verify(membersInsert).execute();
    return expected;
  }

  /** Runs a full test to create a group and returns the expected Group. */
  private Group runCreateGroupTest() throws Exception {
    when(membersInsert.execute()).thenReturn(expectedOwner);
    connection.createGroup("spamlovers@hormel.com");
    Group expected = createExpectedGroup("spamlovers@hormel.com");
    verify(directoryGroups).insert(eq(expected));
    verify(groupsInsert).execute();
    verify(members).insert("spamlovers@hormel.com", expectedOwner);
    verify(membersInsert).execute();
    verify(settingsGroups).patch("spamlovers@hormel.com", getDefaultGroupPermissions());
    verify(groupsSettingsPatch).execute();
    return expected;
  }

  /** Returns a valid GoogleJsonResponseException for the given status code and error message.  */
  private GoogleJsonResponseException makeResponseException(
      final int statusCode,
      final String message) throws Exception {
    HttpTransport transport = new MockHttpTransport() {
      @Override
      public LowLevelHttpRequest buildRequest(String method, String url) {
        return new MockLowLevelHttpRequest() {
          @Override
          public LowLevelHttpResponse execute() {
            MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
            response.setStatusCode(statusCode);
            response.setContentType(Json.MEDIA_TYPE);
            response.setContent(String.format(
                "{\"error\":{\"code\":%d,\"message\":\"%s\",\"domain\":\"global\","
                + "\"reason\":\"duplicate\"}}",
                statusCode,
                message));
            return response;
          }};
      }};
    HttpRequest request = transport.createRequestFactory()
        .buildGetRequest(HttpTesting.SIMPLE_GENERIC_URL)
        .setThrowExceptionOnExecuteError(false);
    return GoogleJsonResponseException.from(new JacksonFactory(), request.execute());
  }
}
