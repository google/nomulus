// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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
import static org.junit.Assert.assertThrows;

import com.beust.jcommander.ParameterException;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import org.junit.jupiter.api.BeforeEach;

@DualDatabaseTest
public class GetPremiumListCommandTest extends CommandTestCase<GetPremiumListCommand> {

  @BeforeEach
  void beforeEach() {
    createTld("tld");
  }

  @TestOfyAndSql
  void testSuccess_list() throws Exception {
    runCommand("tld");
    // can't guarantee ordering of the list entries
    assertInStdout("tld:\n");
    assertInStdout("aluminum,USD 11.00");
  }

  @TestOfyAndSql
  void testSuccess_onlyOneExists() throws Exception {
    runCommand("tld", "nonexistent");
    // can't guarantee ordering of the list entries
    assertInStdout("tld:\n");
    assertInStdout("aluminum,USD 11.00");
    assertInStdout("No list found with name nonexistent.");
  }

  @TestOfyAndSql
  void testFailure_nonexistent() throws Exception {
    runCommand("nonexistent", "othernonexistent");
    assertStdoutIs(
        "No list found with name nonexistent.\nNo list found with name othernonexistent.\n");
  }

  @TestOfyAndSql
  void testFailure_noArgs() {
    assertThrows(ParameterException.class, this::runCommand);
  }
}
