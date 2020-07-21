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
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistResource;
import static org.junit.Assert.assertThrows;

import com.beust.jcommander.ParameterException;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.RegistryNotFoundException;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link UpdateCursorsCommand}. */
class UpdateCursorsCommandTest extends CommandTestCase<UpdateCursorsCommand> {

  private Registry registry;

  @BeforeEach
  void beforeEach() {
    createTld("foo");
    registry = Registry.get("foo");
  }

  void doUpdateTest() throws Exception {
    runCommandForced("--type=brda", "--timestamp=1984-12-18T00:00:00Z", "foo");
    assertThat(ofy().load().key(Cursor.createKey(CursorType.BRDA, registry)).now().getCursorTime())
        .isEqualTo(DateTime.parse("1984-12-18TZ"));
    String changes = command.prompt();
    assertThat(changes)
        .isEqualTo("Change cursorTime of BRDA for Scope:foo to 1984-12-18T00:00:00.000Z\n");
  }

  void doGlobalUpdateTest() throws Exception {
    runCommandForced("--type=recurring_billing", "--timestamp=1984-12-18T00:00:00Z");
    assertThat(
            ofy()
                .load()
                .key(Cursor.createGlobalKey(CursorType.RECURRING_BILLING))
                .now()
                .getCursorTime())
        .isEqualTo(DateTime.parse("1984-12-18TZ"));
    String changes = command.prompt();
    assertThat(changes)
        .isEqualTo(
            "Change cursorTime of RECURRING_BILLING for Scope:GLOBAL to"
                + " 1984-12-18T00:00:00.000Z\n");
  }

  @Test
  void testSuccess_oldValueisEmpty() throws Exception {
    assertThat(ofy().load().key(Cursor.createKey(CursorType.BRDA, registry)).now()).isNull();
    doUpdateTest();
  }

  @Test
  void testSuccess_hasOldValue() throws Exception {
    persistResource(Cursor.create(CursorType.BRDA, DateTime.parse("1950-12-18TZ"), registry));
    doUpdateTest();
  }

  @Test
  void testSuccess_global_hasOldValue() throws Exception {
    persistResource(
        Cursor.createGlobal(CursorType.RECURRING_BILLING, DateTime.parse("1950-12-18TZ")));
    doGlobalUpdateTest();
  }

  @Test
  void testSuccess_global_oldValueisEmpty() throws Exception {
    assertThat(ofy().load().key(Cursor.createGlobalKey(CursorType.RECURRING_BILLING)).now())
        .isNull();
    doGlobalUpdateTest();
  }

  @Test
  void testSuccess_multipleTlds_hasOldValue() throws Exception {
    createTld("bar");
    Registry registry2 = Registry.get("bar");
    persistResource(Cursor.create(CursorType.BRDA, DateTime.parse("1950-12-18TZ"), registry));
    persistResource(Cursor.create(CursorType.BRDA, DateTime.parse("1950-12-18TZ"), registry2));
    runCommandForced("--type=brda", "--timestamp=1984-12-18T00:00:00Z", "foo", "bar");
    assertThat(ofy().load().key(Cursor.createKey(CursorType.BRDA, registry)).now().getCursorTime())
        .isEqualTo(DateTime.parse("1984-12-18TZ"));
    assertThat(ofy().load().key(Cursor.createKey(CursorType.BRDA, registry2)).now().getCursorTime())
        .isEqualTo(DateTime.parse("1984-12-18TZ"));
    String changes = command.prompt();
    assertThat(changes)
        .isEqualTo(
            "Change cursorTime of BRDA for Scope:foo to 1984-12-18T00:00:00.000Z\n"
                + "Change cursorTime of BRDA for Scope:bar to 1984-12-18T00:00:00.000Z\n");
  }

  @Test
  void testSuccess_multipleTlds_oldValueisEmpty() throws Exception {
    createTld("bar");
    Registry registry2 = Registry.get("bar");
    assertThat(ofy().load().key(Cursor.createKey(CursorType.BRDA, registry)).now()).isNull();
    assertThat(ofy().load().key(Cursor.createKey(CursorType.BRDA, registry2)).now()).isNull();
    runCommandForced("--type=brda", "--timestamp=1984-12-18T00:00:00Z", "foo", "bar");
    assertThat(ofy().load().key(Cursor.createKey(CursorType.BRDA, registry)).now().getCursorTime())
        .isEqualTo(DateTime.parse("1984-12-18TZ"));
    assertThat(ofy().load().key(Cursor.createKey(CursorType.BRDA, registry2)).now().getCursorTime())
        .isEqualTo(DateTime.parse("1984-12-18TZ"));
    String changes = command.prompt();
    assertThat(changes)
        .isEqualTo(
            "Change cursorTime of BRDA for Scope:foo to 1984-12-18T00:00:00.000Z\n"
                + "Change cursorTime of BRDA for Scope:bar to 1984-12-18T00:00:00.000Z\n");
  }

  @Test
  void testFailure_badTld() {
    assertThrows(
        RegistryNotFoundException.class,
        () -> runCommandForced("--type=brda", "--timestamp=1984-12-18T00:00:00Z", "bar"));
  }

  @Test
  void testFailure_badCursorType() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () -> runCommandForced("--type=rbda", "--timestamp=1984-12-18T00:00:00Z", "foo"));
    assertThat(thrown).hasMessageThat().contains("Invalid value for --type parameter");
  }
}
