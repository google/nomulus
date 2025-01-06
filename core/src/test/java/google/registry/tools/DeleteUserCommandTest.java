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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.console.User.IAP_SECURED_WEB_APP_USER_ROLE;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.collect.ImmutableMap;
import com.google.common.net.MediaType;
import google.registry.model.console.User;
import google.registry.persistence.VKey;
import google.registry.testing.DatabaseHelper;
import google.registry.tools.server.UpdateUserGroupAction;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link DeleteUserCommand}. */
public class DeleteUserCommandTest extends CommandTestCase<DeleteUserCommand> {

  private final IamClient iamClient = mock(IamClient.class);
  private final ServiceConnection connection = mock(ServiceConnection.class);

  @BeforeEach
  void beforeEach() {
    command.iamClient = iamClient;
    command.maybeGroupEmailAddress = Optional.empty();
    command.setConnection(connection);
  }

  @Test
  void testSuccess_deletesUser() throws Exception {
    DatabaseHelper.createAdminUser("email@example.test");
    VKey<User> key = VKey.create(User.class, "email@example.test");
    assertThat(DatabaseHelper.loadByKeyIfPresent(key)).isPresent();
    runCommandForced("--email", "email@example.test");
    assertThat(DatabaseHelper.loadByKeyIfPresent(key)).isEmpty();
    verify(iamClient).removeBinding("email@example.test", IAP_SECURED_WEB_APP_USER_ROLE);
    verifyNoMoreInteractions(iamClient);
    verifyNoInteractions(connection);
  }

  @Test
  void testSuccess_deletesUser_removeFromGroup() throws Exception {
    command.maybeGroupEmailAddress = Optional.of("group@example.test");
    DatabaseHelper.createAdminUser("email@example.test");
    VKey<User> key = VKey.create(User.class, "email@example.test");
    assertThat(DatabaseHelper.loadByKeyIfPresent(key)).isPresent();
    runCommandForced("--email", "email@example.test");
    assertThat(DatabaseHelper.loadByKeyIfPresent(key)).isEmpty();
    verify(connection)
        .sendPostRequest(
            UpdateUserGroupAction.PATH,
            ImmutableMap.of(
                "userEmailAddress",
                "email@example.test",
                "groupEmailAddress",
                "group@example.test",
                "groupUpdateMode",
                "REMOVE"),
            MediaType.PLAIN_TEXT_UTF_8,
            new byte[0]);
    verifyNoInteractions(iamClient);
    verifyNoMoreInteractions(connection);
  }

  @Test
  void testFailure_nonexistent() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> runCommandForced("--email", "nonexistent@example.test")))
        .hasMessageThat()
        .isEqualTo("Email does not correspond to a valid user");
    verifyNoInteractions(iamClient);
    verifyNoInteractions(connection);
  }
}
