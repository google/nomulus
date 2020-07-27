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

package google.registry.model.common;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.Cursor.CursorType.BRDA;
import static google.registry.model.common.Cursor.CursorType.RDE_UPLOAD;
import static google.registry.model.common.Cursor.CursorType.RECURRING_BILLING;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.schema.cursor.Cursor.GLOBAL;
import static google.registry.schema.cursor.CursorDao.loadAndCompare;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.Assert.assertThrows;

import google.registry.model.EntityTestCase;
import google.registry.model.domain.DomainBase;
import google.registry.model.registry.Registry;
import google.registry.schema.cursor.CursorDao;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Cursor}. */
class CursorTest extends EntityTestCase {

  @BeforeEach
  void setUp() {
    fakeClock.setTo(DateTime.parse("2010-10-17TZ"));
  }

  @Test
  void testSuccess_persistScopedCursor() {
    createTld("tld");
    this.fakeClock.advanceOneMilli();
    final DateTime time = DateTime.parse("2012-07-12T03:30:00.000Z");
    Cursor cursor = Cursor.create(RDE_UPLOAD, time, Registry.get("tld"));
    CursorDao.saveCursor(cursor, "tld");
    assertThat(ofy().load().key(Cursor.createKey(BRDA, Registry.get("tld"))).now()).isNull();
    assertThat(
            ofy()
                .load()
                .key(Cursor.createKey(RDE_UPLOAD, Registry.get("tld")))
                .now()
                .getCursorTime())
        .isEqualTo(time);
    loadAndCompare(cursor, "tld");
  }

  @Test
  void testSuccess_persistGlobalCursor() {
    final DateTime time = DateTime.parse("2012-07-12T03:30:00.000Z");
    CursorDao.saveCursor(Cursor.createGlobal(RECURRING_BILLING, time), GLOBAL);
    assertThat(ofy().load().key(Cursor.createGlobalKey(RECURRING_BILLING)).now().getCursorTime())
        .isEqualTo(time);
    loadAndCompare(Cursor.createGlobal(RECURRING_BILLING, time), GLOBAL);
  }

  @Test
  void testIndexing() throws Exception {
    final DateTime time = DateTime.parse("2012-07-12T03:30:00.000Z");
    CursorDao.saveCursor(Cursor.createGlobal(RECURRING_BILLING, time), GLOBAL);
    Cursor cursor = ofy().load().key(Cursor.createGlobalKey(RECURRING_BILLING)).now();
    loadAndCompare(cursor, GLOBAL);
    verifyIndexing(cursor);
  }

  @Test
  void testFailure_invalidScopeOnCreate() {
    createTld("tld");
    this.fakeClock.advanceOneMilli();
    final DateTime time = DateTime.parse("2012-07-12T03:30:00.000Z");
    final DomainBase domain = persistActiveDomain("notaregistry.tld");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> CursorDao.saveCursor(Cursor.create(RDE_UPLOAD, time, domain), domain.getTld()));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Class required for cursor does not match scope class");
  }

  @Test
  void testFailure_invalidScopeOnKeyCreate() {
    createTld("tld");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> Cursor.createKey(RDE_UPLOAD, persistActiveDomain("notaregistry.tld")));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Class required for cursor does not match scope class");
  }

  @Test
  void testFailure_createGlobalKeyForScopedCursorType() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> Cursor.createGlobalKey(RDE_UPLOAD));
    assertThat(thrown).hasMessageThat().contains("Cursor type is not a global cursor");
  }

  @Test
  void testFailure_invalidScopeOnGlobalKeyCreate() {
    createTld("tld");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> Cursor.createKey(RECURRING_BILLING, persistActiveDomain("notaregistry.tld")));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Class required for cursor does not match scope class");
  }

  @Test
  void testFailure_nullScope() {
    NullPointerException thrown =
        assertThrows(
            NullPointerException.class,
            () -> Cursor.create(RECURRING_BILLING, START_OF_TIME, null));
    assertThat(thrown).hasMessageThat().contains("Cursor scope cannot be null");
  }

  @Test
  void testFailure_nullCursorType() {
    createTld("tld");
    NullPointerException thrown =
        assertThrows(
            NullPointerException.class,
            () -> Cursor.create(null, START_OF_TIME, Registry.get("tld")));
    assertThat(thrown).hasMessageThat().contains("Cursor type cannot be null");
  }

  @Test
  void testFailure_nullTime() {
    createTld("tld");
    NullPointerException thrown =
        assertThrows(
            NullPointerException.class, () -> Cursor.create(RDE_UPLOAD, null, Registry.get("tld")));
    assertThat(thrown).hasMessageThat().contains("Cursor time cannot be null");
  }
}
