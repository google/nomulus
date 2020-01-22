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
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistReservedList;
import static google.registry.testing.DatastoreHelper.persistResource;
import static org.junit.Assert.assertThrows;

import google.registry.model.registry.Registry;
import google.registry.model.registry.label.ReservedList;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link DeleteReservedListCommand}. */
public class DeleteReservedListCommandTest extends CommandTestCase<DeleteReservedListCommand> {

  ReservedList reservedList;

  @Before
  public void init() {
    reservedList = persistReservedList("common", "blah,FULLY_BLOCKED");
  }

  @Test
  public void testSuccess() throws Exception {
    assertThat(reservedList.getReservedListEntries()).hasSize(1);
    runCommandForced("--name=common");
    assertThat(ReservedList.get("common")).isEmpty();
  }

  @Test
  public void testFailure_whenReservedListDoesNotExist() {
    String expectedError =
        "Cannot delete the reserved list doesntExistReservedList because it doesn't exist.";
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--name=doesntExistReservedList"));
    assertThat(thrown).hasMessageThat().contains(expectedError);
  }

  @Test
  public void testFailure_whenReservedListIsInUse() {
    createTld("xn--q9jyb4c");
    persistResource(Registry.get("xn--q9jyb4c").asBuilder().setReservedLists(reservedList).build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--name=" + reservedList.getName()));
    assertThat(ReservedList.get(reservedList.getName())).isPresent();
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Cannot delete reserved list because it is used on these tld(s): xn--q9jyb4c");
  }
}
