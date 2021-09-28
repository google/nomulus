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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.EntityTestCase;
import google.registry.model.domain.DomainBase;
import google.registry.model.tld.Registry;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;

/** Unit tests for {@link Cursor}. */
@DualDatabaseTest
public class CursorTest extends EntityTestCase {

  public CursorTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @BeforeEach
  void setUp() {
    fakeClock.setTo(DateTime.parse("2010-10-17TZ"));
  }

  @TestOfyAndSql
  void testSuccess_persistScopedCursor() {
    createTld("tld");
    this.fakeClock.advanceOneMilli();
    final DateTime time = DateTime.parse("2012-07-12T03:30:00.000Z");
    Cursor cursor = Cursor.create(RDE_UPLOAD, time, Registry.get("tld"));
    tm().transact(() -> tm().put(cursor));
    transactIfJpaTm(
        () -> {
          assertThat(tm().loadByKeyIfPresent(Cursor.createVKey(BRDA, "tld")).isPresent()).isFalse();
          assertThat(tm().loadByKey(Cursor.createVKey(RDE_UPLOAD, "tld")).getCursorTime())
              .isEqualTo(time);
        });
  }

  @TestOfyAndSql
  void testSuccess_persistGlobalCursor() {
    final DateTime time = DateTime.parse("2012-07-12T03:30:00.000Z");
    Cursor cursor = Cursor.createGlobal(RECURRING_BILLING, time);
    tm().transact(() -> tm().put(cursor));
    assertThat(tm().transact(() -> tm().loadByKey(cursor.createVKey())).getCursorTime())
        .isEqualTo(time);
  }

  @TestOfyAndSql
  void testIndexing() throws Exception {
    final DateTime time = DateTime.parse("2012-07-12T03:30:00.000Z");
    tm().transact(() -> tm().put(Cursor.createGlobal(RECURRING_BILLING, time)));
    Cursor cursor = tm().transact(() -> tm().loadByKey(Cursor.createGlobalVKey(RECURRING_BILLING)));
    verifyDatastoreIndexing(cursor);
  }

  @TestOfyAndSql
  void testFailure_invalidScopeOnCreate() {
    createTld("tld");
    this.fakeClock.advanceOneMilli();
    final DateTime time = DateTime.parse("2012-07-12T03:30:00.000Z");
    final DomainBase domain = persistActiveDomain("notaregistry.tld");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> Cursor.create(RDE_UPLOAD, time, domain),
            domain.getTld());
    assertThat(thrown)
        .hasMessageThat()
        .contains("Class required for cursor does not match scope class");
  }

  @TestOfyAndSql
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

  @TestOfyAndSql
  void testFailure_createGlobalKeyForScopedCursorType() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> Cursor.createGlobalKey(RDE_UPLOAD));
    assertThat(thrown).hasMessageThat().contains("Cursor type is not a global cursor");
  }

  @TestOfyAndSql
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

  @TestOfyAndSql
  void testFailure_nullScope() {
    NullPointerException thrown =
        assertThrows(
            NullPointerException.class,
            () -> Cursor.create(RECURRING_BILLING, START_OF_TIME, null));
    assertThat(thrown).hasMessageThat().contains("Cursor scope cannot be null");
  }

  @TestOfyAndSql
  void testFailure_nullCursorType() {
    createTld("tld");
    NullPointerException thrown =
        assertThrows(
            NullPointerException.class,
            () -> Cursor.create(null, START_OF_TIME, Registry.get("tld")));
    assertThat(thrown).hasMessageThat().contains("Cursor type cannot be null");
  }

  @TestOfyAndSql
  void testFailure_nullTime() {
    createTld("tld");
    NullPointerException thrown =
        assertThrows(
            NullPointerException.class, () -> Cursor.create(RDE_UPLOAD, null, Registry.get("tld")));
    assertThat(thrown).hasMessageThat().contains("Cursor time cannot be null");
  }
}
