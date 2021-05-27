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

import static google.registry.testing.DatabaseHelper.persistPremiumList;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ListPremiumListsAction}. */
class ListPremiumListsActionTest extends ListActionTestCase {

  private ListPremiumListsAction action;

  @BeforeEach
  void beforeEach() {
    persistPremiumList("xn--q9jyb4c", "rich,USD 100");
    persistPremiumList("how", "richer,JPY 5000");
    action = new ListPremiumListsAction();
  }

  @Test
  void testRun_noParameters() {
    testRunSuccess(
        action,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        "^how        $",
        "^xn--q9jyb4c$");
  }

  @Test
  void testRun_withBadField_returnsError() {
    testRunError(
        action,
        Optional.of("badfield"),
        Optional.empty(),
        Optional.empty(),
        "^Field 'badfield' not found - recognized fields are:");
  }
}
