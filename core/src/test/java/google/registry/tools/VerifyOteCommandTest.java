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
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.model.registrar.Registrar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link VerifyOteCommand}. */
class VerifyOteCommandTest extends CommandTestCase<VerifyOteCommand> {

  @Mock private AppEngineConnection connection;

  @BeforeEach
  void beforeEach() throws Exception {
    command.setConnection(connection);
    ImmutableMap<String, Object> response =
        ImmutableMap.of("blobio", "Num actions: 19 - Reqs passed: 19/19 - Overall: PASS");
    when(connection.sendJson(anyString(), anyMap()))
        .thenReturn(ImmutableMap.of("blobio", response));
  }

  @Test
  void testSuccess_pass() throws Exception {
    Registrar registrar =
        loadRegistrar("TheRegistrar")
            .asBuilder()
            .setClientId("blobio-1")
            .setRegistrarName("blobio-1")
            .build();
    persistResource(registrar);
    runCommand("blobio");

    verify(connection)
        .sendJson(
            eq("/_dr/admin/verifyOte"),
            eq(ImmutableMap.of("summarize", "false", "registrars", ImmutableList.of("blobio"))));
    assertInStdout("blobio OT&E status");
    assertInStdout("Overall: PASS");
  }

  @MockitoSettings(strictness = Strictness.LENIENT)
  @Test
  void testFailure_registrarDoesntExist() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommand("blobio"));
    assertThat(thrown).hasMessageThat().contains("Registrar blobio does not exist.");
  }

  @MockitoSettings(strictness = Strictness.LENIENT)
  @Test
  void testFailure_noRegistrarsNoCheckAll() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommand(""));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Must provide at least one registrar name, or supply --check_all with no names.");
  }
}
