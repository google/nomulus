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

import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newContact;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistDeletedContact;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GetContactCommand}. */
class GetContactCommandTest extends CommandTestCase<GetContactCommand> {

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    command.clock = fakeClock;
  }

  @Test
  void testSuccess() throws Exception {
    persistActiveContact("sh8013");
    runCommand("sh8013");
    assertInStdout("contactId=sh8013");
    assertInStdout("Websafe key: " + "kind:Contact" + "@sql:rO0ABXQABjItUk9JRA");
  }

  @Test
  void testSuccess_expand() throws Exception {
    persistActiveContact("sh8013");
    runCommand("sh8013", "--expand");
    assertInStdout("contactId=sh8013");
    assertInStdout("Websafe key: " + "kind:Contact" + "@sql:rO0ABXQABjItUk9JRA");
    assertNotInStdout("LiveRef");
  }

  @Test
  void testSuccess_multipleArguments() throws Exception {
    persistActiveContact("sh8013");
    persistActiveContact("jd1234");
    runCommand("sh8013", "jd1234");
    assertInStdout("contactId=sh8013");
    assertInStdout("contactId=jd1234");
    assertInStdout("Websafe key: " + "kind:Contact" + "@sql:rO0ABXQABjItUk9JRA");
    assertInStdout("Websafe key: " + "kind:Contact" + "@sql:rO0ABXQABjMtUk9JRA");
  }

  @Test
  void testSuccess_deletedContact() throws Exception {
    persistDeletedContact("sh8013", fakeClock.nowUtc().minusDays(1));
    runCommand("sh8013");
    assertInStdout("Contact 'sh8013' does not exist or is deleted");
  }

  @Test
  void testSuccess_contactDoesNotExist() throws Exception {
    runCommand("nope");
    assertInStdout("Contact 'nope' does not exist or is deleted");
  }

  @Test
  void testFailure_noContact() {
    assertThrows(ParameterException.class, this::runCommand);
  }

  @Test
  void testSuccess_contactDeletedInFuture() throws Exception {
    persistResource(
        newContact("sh8013").asBuilder().setDeletionTime(fakeClock.nowUtc().plusDays(1)).build());
    runCommand("sh8013", "--read_timestamp=" + fakeClock.nowUtc().plusMonths(1));
    assertInStdout("Contact 'sh8013' does not exist or is deleted");
  }
}
