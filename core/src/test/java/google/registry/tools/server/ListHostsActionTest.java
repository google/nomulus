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

package google.registry.tools.server;

import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistActiveHost;

import google.registry.testing.FakeClock;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ListHostsAction}. */
class ListHostsActionTest extends ListActionTestCase {

  private ListHostsAction action;

  @BeforeEach
  void beforeEach() {
    createTld("foo");
    action = new ListHostsAction();
    action.clock = new FakeClock(DateTime.parse("2000-01-01TZ"));
  }

  @Test
  void testRun_noParameters() {
    testRunSuccess(
        action,
        null,
        null,
        null);
  }

  @Test
  void testRun_twoLinesWithRepoId() {
    persistActiveHost("example2.foo");
    persistActiveHost("example1.foo");
    testRunSuccess(
        action,
        Optional.of("repoId"),
        Optional.empty(),
        Optional.empty(),
        "^fullyQualifiedHostName\\s+repoId\\s*$",
        "^-+\\s+-+\\s*$",
        "^example1.foo\\s+3-ROID\\s*$",
        "^example2.foo\\s+2-ROID\\s*$");
  }

  @Test
  void testRun_twoLinesWithWildcard() {
    persistActiveHost("example2.foo");
    persistActiveHost("example1.foo");
    testRunSuccess(
        action,
        Optional.of("*"),
        Optional.empty(),
        Optional.empty(),
        "^fullyQualifiedHostName\\s+.*repoId",
        "^-+\\s+-+",
        "^example1.foo\\s+.*2",
        "^example2.foo\\s+.*1");
  }

  @Test
  void testRun_twoLinesWithWildcardAndAnotherField() {
    persistActiveHost("example2.foo");
    persistActiveHost("example1.foo");
    testRunSuccess(
        action,
        Optional.of("*,repoId"),
        Optional.empty(),
        Optional.empty(),
        "^fullyQualifiedHostName\\s+.*repoId",
        "^-+\\s+-+",
        "^example1.foo\\s+.*2",
        "^example2.foo\\s+.*1");
  }

  @Test
  void testRun_withBadField_returnsError() {
    persistActiveHost("example2.foo");
    persistActiveHost("example1.foo");
    testRunError(
        action,
        Optional.of("badfield"),
        Optional.empty(),
        Optional.empty(),
        "^Field 'badfield' not found - recognized fields are:");
  }
}
