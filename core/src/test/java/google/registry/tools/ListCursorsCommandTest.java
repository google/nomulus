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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.tld.Tld;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ListCursorsCommand}. */
public class ListCursorsCommandTest extends CommandTestCase<ListCursorsCommand> {

  private static final String HEADER_ONE =
      "TLD                    Cursor Time                Last Update Time";

  private static final String HEADER_TWO =
      "--------------------------------------------------------------------------";

  @BeforeEach
  void beforeEach() {
    fakeClock.setTo(DateTime.parse("1984-12-21T06:07:08.789Z"));
  }

  @Test
  void testlistCursors_globalScoped() throws Exception {
    createTld("com");
    runCommand("--type=RECURRING_BILLING");
  }

  @Test
  void testListCursors_noTlds_printsNothing() throws Exception {
    runCommand("--type=BRDA");
    assertThat(getStdoutAsString()).isEmpty();
  }

  @Test
  void testListCursors_twoTldsOneAbsent_printsAbsentAndTimestampSorted() throws Exception {
    createTlds("foo", "bar");
    persistResource(
        Cursor.createScoped(CursorType.BRDA, DateTime.parse("1984-12-18TZ"), Tld.get("bar")));
    runCommand("--type=BRDA");
    assertThat(getStdoutAsLines())
        .containsExactly(
            HEADER_ONE,
            HEADER_TWO,
            "bar                    1984-12-18T00:00:00.000Z   1984-12-21T06:07:08.789Z",
            "foo                    (absent)                   (absent)")
        .inOrder();
  }

  @Test
  void testListCursors_badCursor_throwsIae() {
    ParameterException thrown =
        assertThrows(ParameterException.class, () -> runCommand("--type=love"));
    assertThat(thrown).hasMessageThat().contains("Invalid value for --type parameter.");
  }

  @Test
  void testListCursors_lowercaseCursor_isAllowed() throws Exception {
    runCommand("--type=brda");
  }

  @Test
  void testListCursors_filterEscrowEnabled_doesWhatItSays() throws Exception {
    createTlds("foo", "bar");
    persistResource(Tld.get("bar").asBuilder().setEscrowEnabled(true).build());
    runCommand("--type=BRDA", "--escrow_enabled");
    assertThat(getStdoutAsLines())
        .containsExactly(
            HEADER_ONE, HEADER_TWO, "bar                    (absent)                   (absent)");
  }
}
