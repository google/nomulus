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

import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistReservedList;
import static google.registry.testing.DatabaseHelper.persistResource;

import google.registry.model.registry.Registry;
import google.registry.model.registry.label.ReservedList;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;

/** Unit tests for {@link ListReservedListsAction}. */
@DualDatabaseTest
class ListReservedListsActionTest extends ListActionTestCase {

  private ListReservedListsAction action;

  @BeforeEach
  void beforeEach() {
    ReservedList rl1 = persistReservedList("xn--q9jyb4c-published", true, "blah,FULLY_BLOCKED");
    ReservedList rl2 = persistReservedList("xn--q9jyb4c-private", false, "dugong,FULLY_BLOCKED");
    createTld("xn--q9jyb4c");
    persistResource(Registry.get("xn--q9jyb4c").asBuilder().setReservedLists(rl1, rl2).build());
    action = new ListReservedListsAction();
  }

  @TestOfyAndSql
  void testRun_noParameters() {
    testRunSuccess(
        action,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        "^xn--q9jyb4c-private\\s*$",
        "^xn--q9jyb4c-published\\s*$");
  }

  @TestOfyAndSql
  void testRun_withParameters() {
    testRunSuccess(
        action,
        Optional.of("shouldPublish"),
        Optional.empty(),
        Optional.empty(),
        "^name\\s+shouldPublish\\s*$",
        "^-+\\s+-+\\s*$",
        "^xn--q9jyb4c-private\\s+false\\s*$",
        "^xn--q9jyb4c-published\\s+true\\s*$");
  }

  @TestOfyAndSql
  void testRun_withWildcard() {
    testRunSuccess(
        action,
        Optional.of("*"),
        Optional.empty(),
        Optional.empty(),
        "^name\\s+.*shouldPublish.*",
        "^-+\\s+-+",
        "^xn--q9jyb4c-private\\s+.*false",
        "^xn--q9jyb4c-published\\s+.*true");
  }

  @TestOfyAndSql
  void testRun_withBadField_returnsError() {
    testRunError(
        action,
        Optional.of("badfield"),
        Optional.empty(),
        Optional.empty(),
        "^Field 'badfield' not found - recognized fields are:");
  }
}
