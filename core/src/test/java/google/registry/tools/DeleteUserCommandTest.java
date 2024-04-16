// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.tools.CreateUserCommand.IAP_SECURED_WEB_APP_USER_ROLE;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserDao;
import google.registry.model.console.UserRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link DeleteUserCommand}. */
public class DeleteUserCommandTest extends CommandTestCase<DeleteUserCommand> {

  private final IamClient iamClient = mock(IamClient.class);

  @BeforeEach
  void beforeEach() {
    command.iamClient = iamClient;
  }

  @Test
  void testSuccess_deletesUser() throws Exception {
    User user =
        new User.Builder()
            .setEmailAddress("email@example.test")
            .setUserRoles(
                new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).setIsAdmin(true).build())
            .build();
    UserDao.saveUser(user);
    assertThat(UserDao.loadUser("email@example.test")).isPresent();
    runCommandForced("--email", "email@example.test");
    assertThat(UserDao.loadUser("email@example.test")).isEmpty();
    verify(iamClient).removeBinding("email@example.test", IAP_SECURED_WEB_APP_USER_ROLE);
    verifyNoMoreInteractions(iamClient);
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
  }
}
