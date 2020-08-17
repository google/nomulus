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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableMap;
import com.google.common.net.MediaType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/** Unit tests for {@link CreateRegistrarGroupsCommand}. */
class CreateRegistrarGroupsCommandTest extends CommandTestCase<CreateRegistrarGroupsCommand> {

  @Mock private AppEngineConnection connection;

  @BeforeEach
  void beforeEach() {
    command.setConnection(connection);
  }

  @Test
  void test_createGroupsForTwoRegistrars() throws Exception {
    runCommandForced("NewRegistrar", "TheRegistrar");
    verify(connection)
        .sendPostRequest(
            eq("/_dr/admin/createGroups"),
            eq(ImmutableMap.of("clientId", "NewRegistrar")),
            eq(MediaType.PLAIN_TEXT_UTF_8),
            eq(new byte[0]));
    verify(connection)
        .sendPostRequest(
            eq("/_dr/admin/createGroups"),
            eq(ImmutableMap.of("clientId", "TheRegistrar")),
            eq(MediaType.PLAIN_TEXT_UTF_8),
            eq(new byte[0]));
    assertInStdout("Success!");
  }

  @Test
  void test_throwsExceptionForNonExistentRegistrar() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("FakeRegistrarThatDefinitelyDoesNotExist"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Could not load registrar with id FakeRegistrarThatDefinitelyDoesNotExist");
  }
}
