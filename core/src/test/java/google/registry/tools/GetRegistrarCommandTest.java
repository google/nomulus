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
import static org.junit.Assert.assertThrows;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GetRegistrarCommand}. */
class GetRegistrarCommandTest extends CommandTestCase<GetRegistrarCommand> {

  @Test
  void testSuccess() throws Exception {
    // This registrar is created by AppEngineRule.
    runCommand("NewRegistrar");
  }

  @Test
  void testSuccess_multipleArguments() throws Exception {
    // Registrars are created by AppEngineRule.
    runCommand("NewRegistrar", "TheRegistrar");
  }

  @Test
  void testFailure_registrarDoesNotExist() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommand("ClientZ"));
    assertThat(thrown).hasMessageThat().contains("Registrar with id ClientZ does not exist");
  }

  @Test
  void testFailure_noRegistrarName() {
    assertThrows(ParameterException.class, this::runCommand);
  }

  @Test
  void testFailure_oneRegistrarDoesNotExist() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommand("NewRegistrar", "ClientZ"));
    assertThat(thrown).hasMessageThat().contains("Registrar with id ClientZ does not exist");
  }
}
