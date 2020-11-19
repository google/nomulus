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

import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.createTlds;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GetTldCommand}. */
class GetTldCommandTest extends CommandTestCase<GetTldCommand> {

  @Test
  void testSuccess() throws Exception {
    createTld("xn--q9jyb4c");
    runCommand("xn--q9jyb4c");
  }

  @Test
  void testSuccess_multipleArguments() throws Exception {
    createTlds("xn--q9jyb4c", "example");
    runCommand("xn--q9jyb4c", "example");
  }

  @Test
  void testFailure_tldDoesNotExist() {
    assertThrows(IllegalArgumentException.class, () -> runCommand("xn--q9jyb4c"));
  }

  @Test
  void testFailure_noTldName() {
    assertThrows(ParameterException.class, this::runCommand);
  }

  @Test
  void testFailure_oneTldDoesNotExist() {
    createTld("xn--q9jyb4c");
    assertThrows(IllegalArgumentException.class, () -> runCommand("xn--q9jyb4c", "example"));
  }
}
