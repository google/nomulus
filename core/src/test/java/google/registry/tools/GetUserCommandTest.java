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


import com.google.common.collect.ImmutableMap;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.testing.DatabaseHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link GetUserCommand}. */
public class GetUserCommandTest extends CommandTestCase<GetUserCommand> {

  @BeforeEach
  void beforeEach() {
    DatabaseHelper.putInDb(
        new User.Builder()
            .setEmailAddress("johndoe@theregistrar.com")
            .setUserRoles(
                new UserRoles.Builder()
                    .setRegistrarRoles(
                        ImmutableMap.of("TheRegistrar", RegistrarRole.PRIMARY_CONTACT))
                    .build())
            .build(),
        new User.Builder()
            .setEmailAddress("fte@google.com")
            .setUserRoles(
                new UserRoles.Builder().setIsAdmin(true).setGlobalRole(GlobalRole.FTE).build())
            .build());
  }

  @Test
  void testSuccess() throws Exception {
    runCommand("fte@google.com");
    assertStdoutIs(
        """
        User: {
            emailAddress=fte@google.com
            registryLockEmailAddress=null
            registryLockPasswordHash=null
            registryLockPasswordSalt=null
            updateTimestamp=UpdateAutoTimestamp: {
                lastUpdateTime=2022-09-01T00:00:00.000Z
            }
            userRoles=UserRoles: {
                globalRole=FTE
                isAdmin=true
                registrarRoles={}
            }
        }
        """);
  }

  @Test
  void testSuccess_multipleUsers() throws Exception {
    runCommand("johndoe@theregistrar.com", "fte@google.com");
    assertStdoutIs(
        """
        User: {
            emailAddress=johndoe@theregistrar.com
            registryLockEmailAddress=null
            registryLockPasswordHash=null
            registryLockPasswordSalt=null
            updateTimestamp=UpdateAutoTimestamp: {
                lastUpdateTime=2022-09-01T00:00:00.000Z
            }
            userRoles=UserRoles: {
                globalRole=NONE
                isAdmin=false
                registrarRoles={TheRegistrar=PRIMARY_CONTACT}
            }
        }
        User: {
            emailAddress=fte@google.com
            registryLockEmailAddress=null
            registryLockPasswordHash=null
            registryLockPasswordSalt=null
            updateTimestamp=UpdateAutoTimestamp: {
                lastUpdateTime=2022-09-01T00:00:00.000Z
            }
            userRoles=UserRoles: {
                globalRole=FTE
                isAdmin=true
                registrarRoles={}
            }
        }
        """);
  }

  @Test
  void testPartialSuccess_partiallyUnknown() throws Exception {
    runCommand("johndoe@theregistrar.com", "asdf@asdf.com");
    assertStdoutIs(
        """
        User: {
            emailAddress=johndoe@theregistrar.com
            registryLockEmailAddress=null
            registryLockPasswordHash=null
            registryLockPasswordSalt=null
            updateTimestamp=UpdateAutoTimestamp: {
                lastUpdateTime=2022-09-01T00:00:00.000Z
            }
            userRoles=UserRoles: {
                globalRole=NONE
                isAdmin=false
                registrarRoles={TheRegistrar=PRIMARY_CONTACT}
            }
        }
        No user with email address asdf@asdf.com
        """);
  }

  @Test
  void testFailure_unknownUser() throws Exception {
    runCommand("asdf@asdf.com");
    assertStdoutIs("No user with email address asdf@asdf.com\n");
  }
}
