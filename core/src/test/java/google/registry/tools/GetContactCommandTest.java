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
import static google.registry.testing.DatabaseHelper.newContactResource;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistDeletedContact;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GetContactCommand}. */
class GetContactCommandTest extends CommandTestCase<GetContactCommand> {

  private DateTime now = DateTime.now(UTC);

  @BeforeEach
  void beforeEach() {
    createTld("tld");
  }

  @Test
  void testSuccess() throws Exception {
    persistActiveContact("sh8013");
    runCommand("sh8013");
    assertInStdout("contactId=sh8013");
    assertInStdout(
        "Websafe key: "
            + "kind:ContactResource"
            + "@sql:rO0ABXQABjItUk9JRA"
            + "@ofy:agR0ZXN0chsLEg9Db250YWN0UmVzb3VyY2UiBjItUk9JRAw");
  }

  @Test
  void testSuccess_expand() throws Exception {
    persistActiveContact("sh8013");
    runCommand("sh8013", "--expand");
    assertInStdout("contactId=sh8013");
    assertInStdout(
        "Websafe key: "
            + "kind:ContactResource"
            + "@sql:rO0ABXQABjItUk9JRA"
            + "@ofy:agR0ZXN0chsLEg9Db250YWN0UmVzb3VyY2UiBjItUk9JRAw");
    assertNotInStdout("LiveRef");
  }

  @Test
  void testSuccess_multipleArguments() throws Exception {
    persistActiveContact("sh8013");
    persistActiveContact("jd1234");
    runCommand("sh8013", "jd1234");
    assertInStdout("contactId=sh8013");
    assertInStdout("contactId=jd1234");
    assertInStdout(
        "Websafe key: "
            + "kind:ContactResource"
            + "@sql:rO0ABXQABjItUk9JRA"
            + "@ofy:agR0ZXN0chsLEg9Db250YWN0UmVzb3VyY2UiBjItUk9JRAw");
    assertInStdout(
        "Websafe key: "
            + "kind:ContactResource"
            + "@sql:rO0ABXQABjItUk9JRA"
            + "@ofy:agR0ZXN0chsLEg9Db250YWN0UmVzb3VyY2UiBjItUk9JRAw");
  }

  @Test
  void testSuccess_deletedContact() throws Exception {
    persistDeletedContact("sh8013", now.minusDays(1));
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
        newContactResource("sh8013").asBuilder().setDeletionTime(now.plusDays(1)).build());
    runCommand("sh8013", "--read_timestamp=" + now.plusMonths(1));
    assertInStdout("Contact 'sh8013' does not exist or is deleted");
  }
}
