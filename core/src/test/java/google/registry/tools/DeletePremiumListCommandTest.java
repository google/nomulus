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
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadPremiumListEntries;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.registry.Registry;
import google.registry.model.registry.label.PremiumList;
import google.registry.model.registry.label.PremiumList.PremiumListEntry;
import google.registry.model.registry.label.PremiumListDualDao;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DeletePremiumListCommand}. */
class DeletePremiumListCommandTest extends CommandTestCase<DeletePremiumListCommand> {

  @Test
  void testSuccess() throws Exception {
    PremiumList premiumList = persistPremiumList("xn--q9jyb4c", "blah,USD 100");
    assertThat(loadPremiumListEntries(premiumList)).hasSize(1);
    runCommand("--force", "--name=xn--q9jyb4c");
    assertThat(PremiumListDualDao.getLatestRevision("xn--q9jyb4c")).isEmpty();

    // Ensure that the Datastore premium list entry entities were deleted correctly.
    assertThat(ofy().load()
        .type(PremiumListEntry.class)
        .ancestor(premiumList.getRevisionKey())
        .keys())
            .isEmpty();
  }

  @Test
  void testFailure_whenPremiumListDoesNotExist() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--name=foo"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Cannot delete the premium list foo because it doesn't exist.");
  }

  @Test
  void testFailure_whenPremiumListIsInUse() {
    PremiumList premiumList = persistPremiumList("xn--q9jyb4c", "blah,USD 100");
    createTld("xn--q9jyb4c");
    persistResource(Registry.get("xn--q9jyb4c").asBuilder().setPremiumList(premiumList).build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--name=" + premiumList.getName()));
    assertThat(PremiumListDualDao.getLatestRevision(premiumList.getName())).isPresent();
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Cannot delete premium list because it is used on these tld(s): xn--q9jyb4c");
  }
}
