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

import static google.registry.testing.DatabaseHelper.persistReservedList;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import org.junit.jupiter.api.BeforeEach;

@DualDatabaseTest
public class GetReservedListCommandTest extends CommandTestCase<GetReservedListCommand> {

  private static final String BASE_LIST_CONTENTS =
      "atlanta,RESERVED_FOR_SPECIFIC_USE # comment\n"
          + "boston,RESERVED_FOR_SPECIFIC_USE\n"
          + "chicago,FULLY_BLOCKED # another comment\n"
          + "dallas,RESERVED_FOR_SPECIFIC_USE # cool city\n"
          + "elpaso,RESERVED_FOR_SPECIFIC_USE\n"
          + "fairbanks,RESERVED_FOR_ANCHOR_TENANT # alaska\n"
          + "greensboro,RESERVED_FOR_SPECIFIC_USE\n";

  @BeforeEach
  void beforeEach() {
    persistReservedList("tld_reserved-terms", BASE_LIST_CONTENTS);
  }

  @TestOfyAndSql
  void testSuccess_list() throws Exception {
    runCommand("-n=tld_reserved-terms");
    assertStdoutIs(BASE_LIST_CONTENTS);
  }

  @TestOfyAndSql
  void testFailure_nonexistent() throws Exception {
    runCommand("-n=nonexistent");
    assertStdoutIs("");
    assertInStderr("No list found with name nonexistent.\n");
  }

  @TestOfyAndSql
  void testFailure_noArgs() {
    assertThrows(ParameterException.class, this::runCommand);
  }
}
